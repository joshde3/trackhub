package io.github.joshde3.trackhub.carriers.track17;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.CREATED;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.IN_TRANSIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.joshde3.trackhub.carriers.CarrierAdapterContractTest;
import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.CarrierUnavailableException;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.port.WebhookNotification;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Track17AdapterTest extends CarrierAdapterContractTest {

    /** A fake key that only exists in this test. The real one is in Railway variables. */
    private static final String KEY = "test-key-not-real";
    private static final TrackingNumber NUMBER = TrackingNumber.of("RR123456789IN");

    @RegisterExtension
    static WireMockExtension carrier = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private Track17Adapter adapterFor(Duration timeout) {
        URI base = URI.create(carrier.baseUrl() + "/track/v2.4");
        return new Track17Adapter(new Track17Settings(base, KEY, timeout));
    }

    @Override
    protected CarrierAdapter adapter() {
        return adapterFor(Duration.ofMillis(500));
    }

    private static String fixture(String name) {
        try (InputStream in = Track17AdapterTest.class.getResourceAsStream("/track17/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void stub(String path, TrackingNumber number, String fixture) {
        carrier.stubFor(post(urlEqualTo("/track/v2.4" + path))
                .withHeader("17token", equalTo(KEY))
                .withRequestBody(matchingJsonPath("$[0].number", equalTo(number.value())))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(fixture(fixture))));
    }

    @Override
    protected TrackingNumber givenAParcelWithScans() {
        stub("/gettrackinfo", NUMBER, "gettrackinfo-in-transit.json");
        return NUMBER;
    }

    @Override
    protected TrackingNumber givenAnUnknownParcel() {
        TrackingNumber unknown = TrackingNumber.of("ZZ000000000XX");
        stub("/gettrackinfo", unknown, "gettrackinfo-not-registered.json");
        stub("/register", unknown, "register-rejected.json");
        return unknown;
    }

    @Override
    protected void givenTheCarrierFails(int httpStatus) {
        carrier.stubFor(post(urlEqualTo("/track/v2.4/gettrackinfo")).willReturn(aResponse().withStatus(httpStatus)));
    }

    @Override
    protected void givenTheCarrierRateLimits(Duration retryAfter) {
        carrier.stubFor(post(urlEqualTo("/track/v2.4/gettrackinfo"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", String.valueOf(retryAfter.toSeconds()))));
    }

    @Override
    protected void givenTheCarrierIsSlow() {
        givenAParcelWithScans();
        carrier.stubFor(post(urlEqualTo("/track/v2.4/gettrackinfo"))
                .willReturn(aResponse().withFixedDelay(2_000).withBody(fixture("gettrackinfo-in-transit.json"))));
    }

    @Override
    protected List<String> knownStatusCodes() {
        return List.of("InfoReceived", "InTransit_PickedUp", "InTransit_Departure", "InTransit_Arrival",
                "InTransit_CustomsProcessing", "InTransit_CustomsReleased", "InTransit_Other",
                "AvailableForPickup_Other", "OutForDelivery_Other", "Delivered_Other",
                "DeliveryFailure_NoBody", "DeliveryFailure_InvalidAddress", "Exception_Damage",
                "Exception_Lost", "Exception_Delayed", "Exception_Returning", "Exception_Returned", "Expired_Other");
    }

    @Nested
    class Tracking {

        @Test
        void readsTheTimelineAndTheRealCarrier() {
            TrackingSnapshot snapshot = adapter().track(givenAParcelWithScans());

            assertThat(snapshot.upstream()).contains("India Post");
            assertThat(snapshot.events()).extracting(TrackingEvent::rawStatus)
                    .containsExactly("InTransit_Arrival", "InTransit_Departure", "InTransit_PickedUp", "InfoReceived");
            TrackingEvent newest = snapshot.events().getFirst();
            assertThat(newest.occurredAt()).isEqualTo(Instant.parse("2026-11-21T04:00:00Z"));
            assertThat(newest.location()).isEqualTo("Pune City HO");
            assertThat(newest.description()).isEqualTo("Item received at delivery office");
        }

        @Test
        void fallsBackToTheLocalTimeAndTreatsAnEmptyLocationAsUnknown() {
            TrackingEvent oldest = adapter().track(givenAParcelWithScans()).events().getLast();

            assertThat(oldest.occurredAt()).isEqualTo(Instant.parse("2026-11-18T04:30:00Z"));
            assertThat(oldest.location()).isNull();
            assertThat(oldest.status()).contains(CREATED);
        }

        @Test
        void registersAnUnregisteredNumberOnceAndWaitsForEvents() {
            stub("/gettrackinfo", NUMBER, "gettrackinfo-not-registered.json");
            stub("/register", NUMBER, "register-accepted.json");

            TrackingSnapshot snapshot = adapter().track(NUMBER);

            assertThat(snapshot.events()).isEmpty();
            carrier.verify(1, postRequestedFor(urlEqualTo("/track/v2.4/register")));
        }

        @Test
        void doesNotSpendQuotaOnANumberItAlreadyTracks() {
            adapter().track(givenAParcelWithScans());

            carrier.verify(0, postRequestedFor(urlEqualTo("/track/v2.4/register")));
        }

        @Test
        void keepsEventsWithUnknownStatusesButLeavesThemUnmapped() {
            carrier.stubFor(post(urlEqualTo("/track/v2.4/gettrackinfo")).willReturn(aResponse().withBody("""
                    {"code":0,"data":{"accepted":[{"number":"RR123456789IN","track_info":{"tracking":{"providers":[
                      {"provider":{"name":""},"events":[
                        {"time_utc":"2026-11-21T04:00:00Z","sub_status":"Teleported_Other"},
                        {"time_utc":"2026-11-21T05:00:00Z","stage":null,"sub_status":null},
                        {"time_utc":"not a time","sub_status":"Delivered_Other"},
                        {"sub_status":"Delivered_Other"}
                      ]}]}}}],"rejected":[]}}
                    """)));

            TrackingSnapshot snapshot = adapter().track(NUMBER);

            assertThat(snapshot.upstream()).isEmpty();
            assertThat(snapshot.events()).extracting(TrackingEvent::rawStatus).containsExactly("Teleported_Other", "Unknown");
            assertThat(snapshot.events()).allSatisfy(event -> assertThat(event.status()).isEmpty());
        }
    }

    @Nested
    class Failures {

        @Test
        void neverPutsTheKeyInTheErrorWhenItIsRejected() {
            givenTheCarrierFails(401);

            assertThatThrownBy(() -> adapter().track(NUMBER))
                    .isInstanceOf(CarrierUnavailableException.class)
                    .hasMessageContaining("rejected the API key")
                    .hasMessageNotContaining(KEY);
        }

        @Test
        void treatsANonZeroResponseCodeAsUnavailable() {
            carrier.stubFor(post(urlEqualTo("/track/v2.4/gettrackinfo")).willReturn(aResponse().withBody("{\"code\":-18010001,\"data\":{}}")));

            assertThatThrownBy(() -> adapter().track(NUMBER)).isInstanceOf(CarrierUnavailableException.class);
        }

        @Test
        void treatsInvalidJsonAsUnavailable() {
            carrier.stubFor(post(urlEqualTo("/track/v2.4/gettrackinfo")).willReturn(aResponse().withBody("<html>gateway</html>")));

            assertThatThrownBy(() -> adapter().track(NUMBER)).isInstanceOf(CarrierUnavailableException.class);
        }

        @Test
        void treatsAnEmptyAnswerAsUnavailable() {
            carrier.stubFor(post(urlEqualTo("/track/v2.4/gettrackinfo")).willReturn(aResponse().withBody("{\"code\":0,\"data\":{\"accepted\":[],\"rejected\":[]}}")));

            assertThatThrownBy(() -> adapter().track(NUMBER)).isInstanceOf(CarrierUnavailableException.class);
        }

        @Test
        void ignoresARetryAfterDate() {
            carrier.stubFor(post(urlEqualTo("/track/v2.4/gettrackinfo"))
                    .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")));

            assertThatThrownBy(() -> adapter().track(NUMBER))
                    .isInstanceOfSatisfying(CarrierUnavailableException.class, e -> assertThat(e.retryAfter()).isEmpty());
        }

        @Test
        void reportsAConnectionFailureAsUnavailable() {
            Track17Adapter unreachable = new Track17Adapter(
                    new Track17Settings(URI.create("http://localhost:1/track/v2.4"), KEY, Duration.ofMillis(500)));

            assertThatThrownBy(() -> unreachable.track(NUMBER)).isInstanceOf(CarrierUnavailableException.class);
        }
    }

    @Nested
    class StatusMapping {

        @ParameterizedTest
        @CsvSource({
            "InfoReceived, CREATED",
            "InTransit_CustomsProcessing, IN_TRANSIT",
            "PickedUp, IN_TRANSIT",
            "AvailableForPickup, OUT_FOR_DELIVERY",
            "OutForDelivery_Other, OUT_FOR_DELIVERY",
            "Delivered_Other, DELIVERED",
            "DeliveryFailure_Security, EXCEPTION",
            "Exception_Lost, EXCEPTION",
            "Expired_Other, EXCEPTION",
            "Exception_Returning, RETURNED",
            "Exception_Returned, RETURNED",
            "Returned, RETURNED",
        })
        void mapsToTheUnifiedStatus(String raw, ShipmentStatus expected) {
            assertThat(adapter().mapStatus(raw)).contains(expected);
        }

        @Test
        void notFoundCarriesNoNews() {
            assertThat(adapter().mapStatus("NotFound_Other")).isEmpty();
        }
    }

    @Nested
    class Webhooks {

        private final Track17Adapter adapter = adapterFor(Duration.ofSeconds(1));
        private final byte[] body = fixture("webhook-tracking-updated.json").getBytes(StandardCharsets.UTF_8);

        private String signatureOf(byte[] raw) {
            byte[] suffix = ("/" + KEY).getBytes(StandardCharsets.UTF_8);
            byte[] signed = new byte[raw.length + suffix.length];
            System.arraycopy(raw, 0, signed, 0, raw.length);
            System.arraycopy(suffix, 0, signed, raw.length, suffix.length);
            return Hashing.sha256Hex(signed);
        }

        @Test
        void acceptsTheCarriersSignatureWhateverTheHeaderCase() {
            assertThat(adapter.verifySignature(Map.of("Sign", signatureOf(body).toUpperCase()), body)).isTrue();
        }

        @Test
        void rejectsAMissingOrForgedSignature() {
            assertThat(adapter.verifySignature(Map.of(), body)).isFalse();
            assertThat(adapter.verifySignature(Map.of("sign", "0".repeat(64)), body)).isFalse();
        }

        @Test
        void rejectsABodyChangedAfterSigning() {
            String signature = signatureOf(body);
            byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("Pune", "Agra").getBytes(StandardCharsets.UTF_8);

            assertThat(adapter.verifySignature(Map.of("sign", signature), tampered)).isFalse();
        }

        @Test
        void parsesAnUpdateWithItsTimeline() {
            WebhookNotification notification = adapter.parseWebhook(body).orElseThrow();

            assertThat(notification).isInstanceOfSatisfying(WebhookNotification.Updated.class, updated -> {
                assertThat(updated.trackingNumber()).isEqualTo(NUMBER);
                assertThat(updated.snapshot().events()).hasSize(4);
                assertThat(updated.snapshot().events()).extracting(TrackingEvent::normalizedStatus)
                        .containsExactly(IN_TRANSIT, IN_TRANSIT, IN_TRANSIT, CREATED);
            });
        }

        @Test
        void givesARetriedDeliveryTheSameId() {
            String first = adapter.parseWebhook(body).orElseThrow().deliveryId();
            String retry = adapter.parseWebhook(body.clone()).orElseThrow().deliveryId();
            String stopped = adapter.parseWebhook(fixture("webhook-tracking-stopped.json").getBytes(StandardCharsets.UTF_8))
                    .orElseThrow().deliveryId();

            assertThat(retry).isEqualTo(first).hasSize(64);
            assertThat(stopped).isNotEqualTo(first);
        }

        @Test
        void parsesTrackingStopped() {
            WebhookNotification notification =
                    adapter.parseWebhook(fixture("webhook-tracking-stopped.json").getBytes(StandardCharsets.UTF_8)).orElseThrow();

            assertThat(notification).isInstanceOf(WebhookNotification.Stopped.class);
            assertThat(notification.trackingNumber()).isEqualTo(NUMBER);
        }

        @Test
        void ignoresEventTypesWeDontActOn() {
            assertThat(adapter.parseWebhook("{\"event\":\"SOMETHING_ELSE\",\"data\":{\"number\":\"RR123456789IN\"}}"
                    .getBytes(StandardCharsets.UTF_8))).isEmpty();
        }

        @Test
        void rejectsBodiesThatAreNotA17TrackWebhook() {
            assertThatThrownBy(() -> adapter.parseWebhook("not json".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> adapter.parseWebhook("{\"event\":\"TRACKING_UPDATED\",\"data\":{}}".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Settings {

        @Test
        void refusesToStartWithoutAKey() {
            assertThatThrownBy(() -> new Track17Settings(Track17Settings.PRODUCTION, " ", Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRACK17_API_KEY");
        }

        @Test
        void neverPrintsTheKey() {
            assertThat(new Track17Settings(Track17Settings.PRODUCTION, KEY, Duration.ofSeconds(5)).toString())
                    .doesNotContain(KEY)
                    .contains("api.17track.net");
        }
    }

    @Test
    void identifiesItselfAndAcceptsAnyNumber() {
        assertThat(adapter().code()).isEqualTo(Track17Adapter.CODE);
        assertThat(adapter().recognises(TrackingNumber.of("1Z999AA10123456784"))).isTrue();
    }
}
