package io.github.joshde3.trackhub.app.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joshde3.trackhub.app.FakeCarrierConfiguration;
import io.github.joshde3.trackhub.app.PostgresTestConfiguration;
import io.github.joshde3.trackhub.carriers.track17.Track17Adapter;
import io.github.joshde3.trackhub.app.persistence.WebhookDeliveries;
import io.github.joshde3.trackhub.app.shipment.CarrierLookup;
import io.github.joshde3.trackhub.app.shipment.TrackingCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository.ConcurrentUpdateException;
import io.github.joshde3.trackhub.domain.port.WebhookNotification;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real HTTP against a running server and real Postgres, because the property under test,
 * "each delivery is applied once even when copies arrive together", lives in the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, FakeCarrierConfiguration.class})
class WebhookTest {

    /** Same fake key as src/test/resources/config/application.yml. */
    private static final String KEY = "test-key-not-real";
    private static final AtomicInteger NUMBERS = new AtomicInteger(500_000);

    @LocalServerPort private int port;
    @Autowired private ShipmentRepository shipments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebhookDeliveries deliveries;
    @Autowired private TransactionTemplate transaction;
    @Autowired private Track17Adapter track17;
    @Autowired private CarrierLookup lookup;
    @Autowired private CacheManager caches;
    @Autowired private MeterRegistry metrics;

    private final HttpClient client = HttpClient.newHttpClient();
    private TrackingNumber number;

    @BeforeEach
    void newParcel() {
        number = TrackingNumber.of("LP" + NUMBERS.incrementAndGet() + "00CN");
    }

    private UUID trackedBy() {
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, google_sub, email) VALUES (?, ?, ?)", owner, "sub-" + owner, owner + "@example.com");
        return shipments.save(Shipment.register(owner, Track17Adapter.CODE, number, Clock.systemUTC())).id();
    }

    private String updated() {
        return """
                {"event":"TRACKING_UPDATED","data":{"number":"%s","carrier":3011,"track_info":{"tracking":{"providers":[
                  {"provider":{"name":"China Post"},"events":[
                    {"time_utc":"2026-11-21T08:00:00Z","description":"Out for delivery","location":"Pune","sub_status":"OutForDelivery_Other"},
                    {"time_utc":"2026-11-20T12:00:00Z","description":"Arrived","location":"Mumbai","sub_status":"InTransit_Arrival"}
                  ]}]}}}}""".formatted(number.value());
    }

    private static String sign(String body, String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((body + "/" + key).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private HttpResponse<String> deliver(String carrier, String body, String signature) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/" + carrier))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("sign", signature);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deliver(String body) throws Exception {
        return deliver("track17", body, sign(body, KEY));
    }

    private long deliveriesFor(TrackingNumber n) {
        return jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE tracking_number = ?", Long.class, n.value());
    }

    private long eventsOf(UUID shipmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM tracking_events WHERE shipment_id = ?", Long.class, shipmentId);
    }

    private ShipmentStatus statusOf(UUID shipmentId) {
        return ShipmentStatus.valueOf(jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipmentId));
    }

    @Test
    void appliesASignedUpdateToEveryUserTrackingTheParcel() throws Exception {
        UUID alices = trackedBy();
        UUID bobs = trackedBy();

        HttpResponse<String> response = deliver(updated());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("applied");
        for (UUID copy : List.of(alices, bobs)) {
            assertThat(statusOf(copy)).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
            assertThat(eventsOf(copy)).isEqualTo(2);
        }
    }

    @Test
    void cachesTheWebhooksAnswerSoTheNextRefreshNeedsNoCarrierCall() throws Exception {
        trackedBy();

        deliver(updated());

        TrackingSnapshot cached = caches.getCache(TrackingCache.NAME)
                .get("track17:" + number.value(), TrackingSnapshot.class);
        assertThat(cached).isNotNull();
        assertThat(cached.events()).hasSize(2);
    }

    @Test
    void absorbsARetriedDelivery() throws Exception {
        UUID shipment = trackedBy();
        double duplicatesBefore = metrics.counter("trackhub.webhooks", "carrier", "track17", "result", "duplicate").count();

        deliver(updated());
        HttpResponse<String> retry = deliver(updated());

        assertThat(metrics.counter("trackhub.webhooks", "carrier", "track17", "result", "duplicate").count() - duplicatesBefore)
                .isEqualTo(1);

        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).contains("duplicate");
        assertThat(deliveriesFor(number)).isEqualTo(1);
        assertThat(eventsOf(shipment)).isEqualTo(2);
    }

    @Test
    void appliesTenSimultaneousCopiesExactlyOnce() throws Exception {
        UUID shipment = trackedBy();
        String body = updated();
        String signature = sign(body, KEY);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> responses = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                responses.add(pool.submit(() -> {
                    start.await();
                    return deliver("track17", body, signature);
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<HttpResponse<String>> response : responses) {
                assertThat(response.get().statusCode()).isEqualTo(200);
                results.add(response.get().body());
            }
            assertThat(results).filteredOn(r -> r.contains("applied")).hasSize(1);
            assertThat(results).filteredOn(r -> r.contains("duplicate")).hasSize(9);
        }
        assertThat(deliveriesFor(number)).isEqualTo(1);
        assertThat(eventsOf(shipment)).isEqualTo(2);
        // One save, so one version bump: the nine copies did no work at all.
        assertThat(jdbc.queryForObject("SELECT version FROM shipments WHERE id = ?", Long.class, shipment)).isEqualTo(1L);
    }

    @Test
    void rejectsAMissingOrForgedSignatureAndStoresNothing() throws Exception {
        UUID shipment = trackedBy();

        assertThat(deliver("track17", updated(), null).statusCode()).isEqualTo(401);
        assertThat(deliver("track17", updated(), sign(updated(), "guessed-key")).statusCode()).isEqualTo(401);
        assertThat(deliveriesFor(number)).isZero();
        assertThat(eventsOf(shipment)).isZero();
    }

    @Test
    void answersNotFoundForCarriersThatDontPush() throws Exception {
        assertThat(deliver("fake", updated(), "x").statusCode()).isEqualTo(404);
        assertThat(deliver("dhl", updated(), "x").statusCode()).isEqualTo(404);
        assertThat(deliver("NOT_A_CODE", updated(), "x").statusCode()).isEqualTo(404);
    }

    @Test
    void recordsAStoppedParcel() throws Exception {
        trackedBy();
        String stopped = "{\"event\":\"TRACKING_STOPPED\",\"data\":{\"number\":\"" + number.value() + "\"}}";

        assertThat(deliver(stopped).body()).contains("applied");
        assertThat(jdbc.queryForObject("SELECT kind FROM webhook_deliveries WHERE tracking_number = ?", String.class, number.value()))
                .isEqualTo("STOPPED");
    }

    @Test
    void ignoresEventTypesWeDontUseAndRejectsGarbage() throws Exception {
        String other = "{\"event\":\"SOMETHING_NEW\",\"data\":{\"number\":\"" + number.value() + "\"}}";
        assertThat(deliver(other).body()).contains("ignored");

        assertThat(deliver("not json").statusCode()).isEqualTo(400);
        assertThat(deliveriesFor(number)).isZero();
    }

    @Test
    void acceptsAnUpdateForAParcelNobodyTracks() throws Exception {
        assertThat(deliver(updated()).statusCode()).isEqualTo(200);
        assertThat(deliveriesFor(number)).isEqualTo(1);
    }

    /** The real repository, except that {@code save} runs {@code beforeSave} first. */
    private ShipmentRepository saving(Runnable beforeSave) {
        return new ShipmentRepository() {
            @Override public Shipment save(Shipment shipment) {
                beforeSave.run();
                return shipments.save(shipment);
            }
            @Override public Optional<Shipment> findForOwner(UUID shipmentId, UUID ownerId) {
                return shipments.findForOwner(shipmentId, ownerId);
            }
            @Override public Optional<Shipment> findByTracking(UUID ownerId, CarrierCode carrier, TrackingNumber trackingNumber) {
                return shipments.findByTracking(ownerId, carrier, trackingNumber);
            }
            @Override public List<Shipment> findAllTracking(CarrierCode carrier, TrackingNumber trackingNumber) {
                return shipments.findAllTracking(carrier, trackingNumber);
            }
        };
    }

    private WebhookNotification parsed() {
        return track17.parseWebhook(updated().getBytes(StandardCharsets.UTF_8)).orElseThrow();
    }

    @Test
    void retriesOnceWhenARefreshSavedTheParcelMidway() {
        UUID shipment = trackedBy();
        AtomicInteger saves = new AtomicInteger();
        ShipmentRepository losesTheFirstRace = saving(() -> {
            if (saves.getAndIncrement() == 0) {
                throw new ConcurrentUpdateException(shipment, null);
            }
        });

        WebhookIngestService.Outcome outcome =
                new WebhookIngestService(deliveries, losesTheFirstRace, transaction, lookup).ingest(Track17Adapter.CODE, parsed());

        // APPLIED, not DUPLICATE: the first attempt's delivery row was rolled back with it.
        assertThat(outcome).isEqualTo(WebhookIngestService.Outcome.APPLIED);
        assertThat(saves).hasValue(2);
        assertThat(deliveriesFor(number)).isEqualTo(1);
        assertThat(eventsOf(shipment)).isEqualTo(2);
    }

    @Test
    void forgetsTheDeliveryWhenApplyingItFailsSoTheCarriersRetryWorks() {
        UUID shipment = trackedBy();
        ShipmentRepository broken = saving(() -> {
            throw new IllegalStateException("database fell over");
        });

        assertThatThrownBy(() -> new WebhookIngestService(deliveries, broken, transaction, lookup).ingest(Track17Adapter.CODE, parsed()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(deliveriesFor(number)).isZero();

        // The carrier retries the same delivery later, and this time it is applied.
        assertThat(new WebhookIngestService(deliveries, shipments, transaction, lookup).ingest(Track17Adapter.CODE, parsed()))
                .isEqualTo(WebhookIngestService.Outcome.APPLIED);
        assertThat(eventsOf(shipment)).isEqualTo(2);
    }
}
