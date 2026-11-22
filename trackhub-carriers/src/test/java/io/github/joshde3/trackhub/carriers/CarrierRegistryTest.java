package io.github.joshde3.trackhub.carriers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.port.WebhookCapable;
import io.github.joshde3.trackhub.domain.port.WebhookNotification;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class CarrierRegistryTest {

    private static class FakeAdapter implements CarrierAdapter {
        private final CarrierCode code;
        private final Predicate<String> recognises;

        FakeAdapter(String code, Predicate<String> recognises) {
            this.code = new CarrierCode(code);
            this.recognises = recognises;
        }

        @Override public CarrierCode code() { return code; }
        @Override public boolean recognises(TrackingNumber number) { return recognises.test(number.value()); }
        @Override public TrackingSnapshot track(TrackingNumber number) { return new TrackingSnapshot(number, null, List.of()); }
        @Override public Optional<ShipmentStatus> mapStatus(String rawStatus) { return Optional.empty(); }
    }

    private static final class FakeWebhookAdapter extends FakeAdapter implements WebhookCapable {
        FakeWebhookAdapter(String code) { super(code, n -> true); }
        @Override public boolean verifySignature(Map<String, String> headers, byte[] rawBody) { return true; }
        @Override public Optional<WebhookNotification> parseWebhook(byte[] rawBody) { return Optional.empty(); }
    }

    private final FakeAdapter indiaPost = new FakeAdapter("india-post", n -> n.endsWith("IN"));
    private final FakeAdapter delhivery = new FakeAdapter("delhivery", n -> n.matches("\\d{13}"));
    private final FakeWebhookAdapter aggregator = new FakeWebhookAdapter("track17");
    private final CarrierRegistry registry = new CarrierRegistry(List.of(indiaPost, delhivery), aggregator);

    @Test
    void prefersADirectCarrierThatRecognisesTheNumber() {
        assertThat(registry.detect(TrackingNumber.of("RR123456789IN"))).isSameAs(indiaPost);
        assertThat(registry.detect(TrackingNumber.of("1234567890123"))).isSameAs(delhivery);
    }

    @Test
    void fallsBackToTheAggregator() {
        assertThat(registry.detect(TrackingNumber.of("1Z999AA10123456784"))).isSameAs(aggregator);
    }

    @Test
    void findsAdaptersByStoredCode() {
        assertThat(registry.byCode(new CarrierCode("delhivery"))).containsSame(delhivery);
        assertThat(registry.byCode(new CarrierCode("dhl"))).isEmpty();
        assertThat(registry.codes()).extracting(CarrierCode::value).containsExactly("india-post", "delhivery", "track17");
    }

    @Test
    void offersWebhooksOnlyForCarriersThatSendThem() {
        assertThat(registry.webhookReceiver(new CarrierCode("track17"))).containsSame(aggregator);
        assertThat(registry.webhookReceiver(new CarrierCode("india-post"))).isEmpty();
        assertThat(registry.webhookReceiver(new CarrierCode("dhl"))).isEmpty();
    }

    @Test
    void rejectsTwoAdaptersWithTheSameCode() {
        assertThatThrownBy(() -> new CarrierRegistry(List.of(indiaPost, new FakeAdapter("india-post", n -> false)), aggregator))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
