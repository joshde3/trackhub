package io.github.joshde3.trackhub.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarrierPortTypesTest {

    private static final TrackingNumber NUMBER = TrackingNumber.of("RR123456789IN");

    @Test
    void snapshotCopiesItsEventsSoTheAdapterCantChangeThemLater() {
        List<TrackingEvent> events = new ArrayList<>();
        events.add(new TrackingEvent(Instant.parse("2026-11-20T12:00:00Z"), "IT", null, null, null));

        TrackingSnapshot snapshot = new TrackingSnapshot(NUMBER, null, events);
        events.clear();

        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.upstream()).isEmpty();
        assertThat(new TrackingSnapshot(NUMBER, "India Post", List.of()).upstream()).contains("India Post");
    }

    @Test
    void unavailableSaysHowLongToWaitOnlyWhenTheCarrierDid() {
        assertThat(new CarrierUnavailableException("down", null).retryAfter()).isEmpty();
        assertThat(new CarrierUnavailableException("slow down", Duration.ofSeconds(3), null).retryAfter())
                .contains(Duration.ofSeconds(3));
    }

    @Test
    void notFoundKeepsTheNumber() {
        TrackingNotFoundException e = new TrackingNotFoundException(NUMBER, "no such parcel");

        assertThat(e.trackingNumber()).isEqualTo(NUMBER);
        assertThat(e).hasMessageContaining("RR123456789IN").hasMessageContaining("no such parcel");
    }

    @Test
    void webhookNotificationsNameTheirParcel() {
        WebhookNotification updated = new WebhookNotification.Updated("d1", new TrackingSnapshot(NUMBER, null, List.of()));
        WebhookNotification stopped = new WebhookNotification.Stopped("d2", NUMBER);

        assertThat(updated.trackingNumber()).isEqualTo(NUMBER);
        assertThat(stopped.trackingNumber()).isEqualTo(NUMBER);
        assertThatThrownBy(() -> new WebhookNotification.Stopped(null, NUMBER)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WebhookNotification.Updated("d3", null)).isInstanceOf(NullPointerException.class);
    }
}
