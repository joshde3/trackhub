package io.github.joshde3.trackhub.domain.port;

import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a carrier told us about one parcel in one call: its events, in whatever order the carrier
 * sent them. {@code Shipment.applyEvents} sorts, de-duplicates and decides the status.
 *
 * @param trackingNumber the parcel
 * @param upstreamCarrier for an aggregator, the real carrier it found (for example "India Post");
 *     {@code null} when unknown or when the adapter is the carrier itself
 * @param events every event the carrier reported, possibly including ones we already stored
 */
public record TrackingSnapshot(TrackingNumber trackingNumber, String upstreamCarrier, List<TrackingEvent> events) {

    public TrackingSnapshot {
        Objects.requireNonNull(trackingNumber, "trackingNumber");
        events = List.copyOf(events);
    }

    public Optional<String> upstream() {
        return Optional.ofNullable(upstreamCarrier);
    }
}
