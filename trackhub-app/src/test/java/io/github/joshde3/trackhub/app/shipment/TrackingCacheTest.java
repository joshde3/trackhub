package io.github.joshde3.trackhub.app.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TrackingCacheTest {

    private static TrackingSnapshot snapshot(TrackingEvent... events) {
        return new TrackingSnapshot(TrackingNumber.of("RR123456789IN"), null, List.of(events));
    }

    private static TrackingEvent at(String time, ShipmentStatus status) {
        return new TrackingEvent(Instant.parse(time), "raw", status, null, null);
    }

    @ParameterizedTest
    @CsvSource({
        "OUT_FOR_DELIVERY, PT5M",
        "IN_TRANSIT, PT30M",
        "EXCEPTION, PT30M",
        "CREATED, PT2H",
        "DELIVERED, PT24H",
        "RETURNED, PT24H",
    })
    void staysFreshLongerTheLessLikelyTheParcelIsToChange(ShipmentStatus status, Duration ttl) {
        assertThat(TrackingCache.ttlFor(snapshot(at("2026-11-21T08:00:00Z", status)))).isEqualTo(ttl);
    }

    @Test
    void checksAgainSoonWhenTheCarrierHasNoScansYet() {
        assertThat(TrackingCache.ttlFor(snapshot())).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void goesByTheNewestMappedEventWhateverTheOrder() {
        TrackingSnapshot snapshot = snapshot(
                at("2026-11-21T08:00:00Z", ShipmentStatus.DELIVERED),
                at("2026-11-22T09:00:00Z", null), // unmapped carrier status: ignored
                at("2026-11-20T08:00:00Z", ShipmentStatus.IN_TRANSIT));

        assertThat(TrackingCache.latestStatus(snapshot)).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(TrackingCache.latestStatus(snapshot(at("2026-11-22T09:00:00Z", null)))).isEqualTo(ShipmentStatus.CREATED);
    }
}
