package io.github.joshde3.trackhub.app.shipment;

import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import java.time.Duration;
import java.util.Comparator;

/**
 * How long a carrier's answer stays fresh, by what it says about the parcel (ADR 0006). A parcel
 * out for delivery changes within the hour; a delivered one never changes again. One fixed TTL
 * would be either too stale for the first or wasteful for the second.
 */
public final class TrackingCache {

    public static final String NAME = "tracking";
    public static final int MAX_ENTRIES = 10_000;

    private TrackingCache() {}

    public static Duration ttlFor(TrackingSnapshot snapshot) {
        if (snapshot.events().isEmpty()) {
            // Just registered with an aggregator: the first scans usually appear within minutes.
            return Duration.ofMinutes(2);
        }
        return switch (latestStatus(snapshot)) {
            case OUT_FOR_DELIVERY -> Duration.ofMinutes(5);
            case IN_TRANSIT, EXCEPTION -> Duration.ofMinutes(30);
            case CREATED -> Duration.ofHours(2);
            case DELIVERED, RETURNED -> Duration.ofHours(24);
        };
    }

    /** The newest mapped event's status; CREATED if no event is mapped. */
    static ShipmentStatus latestStatus(TrackingSnapshot snapshot) {
        return snapshot.events().stream()
                .filter(event -> event.normalizedStatus() != null)
                .max(Comparator.comparing(TrackingEvent::occurredAt))
                .map(TrackingEvent::normalizedStatus)
                .orElse(ShipmentStatus.CREATED);
    }

    static String key(String carrier, String trackingNumber) {
        return carrier + ":" + trackingNumber;
    }
}
