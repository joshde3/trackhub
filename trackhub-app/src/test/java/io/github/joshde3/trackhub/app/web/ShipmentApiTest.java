package io.github.joshde3.trackhub.app.web;

import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.IN_TRANSIT;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.OUT_FOR_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joshde3.trackhub.app.FakeCarrier;
import io.github.joshde3.trackhub.app.FakeCarrierConfiguration;
import io.github.joshde3.trackhub.app.PostgresTestConfiguration;
import io.github.joshde3.trackhub.app.shipment.TrackingCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, FakeCarrierConfiguration.class})
class ShipmentApiTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger(100_000);

    @Autowired private MockMvc http;
    @Autowired private FakeCarrier carrier;
    @Autowired private ObjectMapper json;
    @Autowired private ShipmentRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CacheManager caches;
    @Autowired private MeterRegistry metrics;

    private RequestPostProcessor alice;
    private RequestPostProcessor bob;
    private TrackingNumber number;

    /** A token as Spring sees it after verification; the decoder itself is tested separately. */
    private static RequestPostProcessor user(String name) {
        String sub = name + "-" + UUID.randomUUID();
        return jwt().jwt(token -> token.subject(sub).claim("email", sub + "@example.com").claim("name", name));
    }

    private static TrackingEvent event(String at, String raw, ShipmentStatus status) {
        return new TrackingEvent(Instant.parse(at), raw, status, "Pune", "scan " + raw);
    }

    @BeforeEach
    void setUp() {
        alice = user("alice");
        bob = user("bob");
        number = TrackingNumber.of("RR" + NUMBERS.incrementAndGet() + "000IN");
    }

    /** Lets the cached carrier answer expire, as if its TTL had passed. */
    private void cacheExpires() {
        caches.getCache(TrackingCache.NAME).clear();
    }

    private MockHttpServletRequestBuilder addRequest(String body) {
        return post("/api/shipments").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode add(RequestPostProcessor who, String trackingNumber) throws Exception {
        String body = http.perform(addRequest("{\"trackingNumber\":\"" + trackingNumber + "\"}").with(who))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void rejectsRequestsWithoutAToken() throws Exception {
        http.perform(get("/api/shipments")).andExpect(status().isUnauthorized());
    }

    @Test
    void addsAParcelWithItsTimeline() throws Exception {
        carrier.knows(number, event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT), event("2026-11-21T08:00:00Z", "OFD", OUT_FOR_DELIVERY));

        http.perform(addRequest("{\"trackingNumber\":\"" + number.value().toLowerCase() + "\"}").with(alice))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/shipments/")))
                .andExpect(jsonPath("$.trackingNumber").value(number.value()))
                .andExpect(jsonPath("$.carrier").value("fake"))
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].rawStatus").value("IT"));
    }

    @Test
    void refusesToTrackTheSameParcelTwice() throws Exception {
        add(alice, number.value());

        http.perform(addRequest("{\"trackingNumber\":\"" + number.value() + "\"}").with(alice))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already-tracking"));
    }

    @Test
    void savesNothingWhenTheCarrierHasNoSuchParcel() throws Exception {
        carrier.doesNotKnow(number);

        http.perform(addRequest("{\"trackingNumber\":\"" + number.value() + "\"}").with(alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("unknown-tracking-number"));
        http.perform(get("/api/shipments").with(alice)).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void stillAddsTheParcelWhenTheCarrierIsDown() throws Exception {
        carrier.isDownFor(number);

        JsonNode added = add(alice, number.value());

        assertThat(added.path("status").asText()).isEqualTo("CREATED");
        assertThat(added.path("events")).isEmpty();
    }

    @Test
    void explainsInvalidInput() throws Exception {
        http.perform(addRequest("{\"trackingNumber\":\"#!\"}").with(alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-input"));
        http.perform(addRequest("{\"trackingNumber\":\"\"}").with(alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        http.perform(addRequest("{\"trackingNumber\":\"" + number.value() + "\",\"carrier\":\"dhl\"}").with(alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown-carrier"));
        http.perform(get("/api/shipments?size=1000").with(alice)).andExpect(status().isBadRequest());
    }

    @Test
    void hidesOtherUsersParcels() throws Exception {
        String id = add(alice, number.value()).path("id").asText();

        http.perform(get("/api/shipments/" + id).with(bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("shipment-not-found"));
        http.perform(get("/api/shipments").with(bob)).andExpect(jsonPath("$.items", hasSize(0)));
        http.perform(get("/api/shipments/" + id).with(alice)).andExpect(status().isOk());
    }

    @Test
    void listsNewestFirstOnePageAtATime() throws Exception {
        String first = add(alice, number.value()).path("id").asText();
        String second = add(alice, "RR" + NUMBERS.incrementAndGet() + "000IN").path("id").asText();
        String third = add(alice, "RR" + NUMBERS.incrementAndGet() + "000IN").path("id").asText();

        http.perform(get("/api/shipments?size=2").with(alice))
                .andExpect(jsonPath("$.items[0].id").value(third))
                .andExpect(jsonPath("$.items[1].id").value(second))
                .andExpect(jsonPath("$.hasMore").value(true));
        http.perform(get("/api/shipments?size=2&page=1").with(alice))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(first))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void refreshAddsNewScans() throws Exception {
        carrier.knows(number, event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT));
        String id = add(alice, number.value()).path("id").asText();
        carrier.knows(number, event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT), event("2026-11-21T08:00:00Z", "OFD", OUT_FOR_DELIVERY));
        cacheExpires();

        http.perform(post("/api/shipments/" + id + "/refresh").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.events", hasSize(2)));
    }

    @Test
    void refreshingAgainWithinTheTtlDoesNotCallTheCarrier() throws Exception {
        carrier.knows(number, event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT));
        String id = add(alice, number.value()).path("id").asText();
        double callsBefore = carrierCalls();

        for (int i = 0; i < 3; i++) {
            http.perform(post("/api/shipments/" + id + "/refresh").with(alice)).andExpect(status().isOk());
        }

        assertThat(carrier.callsFor(number)).isEqualTo(1);
        assertThat(carrierCalls() - callsBefore).isZero();
    }

    private double carrierCalls() {
        return metrics.find("trackhub.carrier.calls").tag("carrier", "fake").timers().stream()
                .mapToDouble(timer -> timer.count()).sum();
    }

    @Test
    void neverCachesAFailure() throws Exception {
        carrier.isDownFor(number);
        String id = add(alice, number.value()).path("id").asText();
        cacheExpires();

        http.perform(post("/api/shipments/" + id + "/refresh").with(alice)).andExpect(status().isServiceUnavailable());
        carrier.knows(number, event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT));

        http.perform(post("/api/shipments/" + id + "/refresh").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    }

    @Test
    void tagsEveryResponseWithARequestId() throws Exception {
        http.perform(get("/api/shipments").with(alice)).andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
        http.perform(get("/api/shipments").with(alice).header("X-Request-Id", "app-1234-abcd"))
                .andExpect(header().string("X-Request-Id", "app-1234-abcd"));
        // A hostile id (log injection) is replaced, not echoed.
        http.perform(get("/api/shipments").with(alice).header("X-Request-Id", "x\n{\"level\":\"ERROR\"}"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void refreshKeepsAWebhookUpdateThatLandedWhileItWaitedOnTheCarrier() throws Exception {
        String id = add(alice, number.value()).path("id").asText();
        carrier.knows(number, event("2026-11-21T08:00:00Z", "OFD", OUT_FOR_DELIVERY));
        cacheExpires();
        // While refresh waits on the carrier, a webhook saves a scan the carrier call won't return.
        carrier.duringNextCall(number, () -> {
            Shipment fromWebhook = repository.findForOwner(UUID.fromString(id), ownerOf(id)).orElseThrow();
            fromWebhook.applyEvents(List.of(event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT)));
            repository.save(fromWebhook);
        });

        // Refresh's first save loses the version race, reloads, and applies its events on top.
        http.perform(post("/api/shipments/" + id + "/refresh").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.events", hasSize(2)));
    }

    private UUID ownerOf(String shipmentId) {
        return jdbc.queryForObject("SELECT user_id FROM shipments WHERE id = ?", UUID.class, UUID.fromString(shipmentId));
    }

    @Test
    void tellsTheClientWhenToRetryIfTheCarrierIsDown() throws Exception {
        String id = add(alice, number.value()).path("id").asText();
        carrier.isDownFor(number);
        cacheExpires();

        http.perform(post("/api/shipments/" + id + "/refresh").with(alice))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.code").value("carrier-unavailable"));
    }

    @Test
    void publishesTheApiDocumentWithoutSignIn() throws Exception {
        http.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/shipments']").exists())
                .andExpect(jsonPath("$.components.securitySchemes['google-id-token'].scheme").value("bearer"));
    }
}
