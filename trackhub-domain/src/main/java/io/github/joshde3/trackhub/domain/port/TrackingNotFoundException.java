package io.github.joshde3.trackhub.domain.port;

import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;

/**
 * The carrier answered, and it has no such parcel (or rejected the number). Retrying won't help,
 * so callers don't. Maps to HTTP 422 when a user adds the number.
 */
public final class TrackingNotFoundException extends RuntimeException {

    private final TrackingNumber trackingNumber;

    public TrackingNotFoundException(TrackingNumber trackingNumber, String reason) {
        super("Carrier has no parcel " + trackingNumber + ": " + reason);
        this.trackingNumber = trackingNumber;
    }

    public TrackingNumber trackingNumber() {
        return trackingNumber;
    }
}
