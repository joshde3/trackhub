package io.github.joshde3.trackhub.domain.port;

import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.util.Objects;

/**
 * One webhook from a carrier, already verified and parsed.
 *
 * <p>{@link #deliveryId()} is the idempotency key: the webhook endpoint stores it under a unique
 * constraint before doing any work, so a carrier retrying the same delivery is absorbed (ADR 0005).
 */
public sealed interface WebhookNotification {

    /** The carrier's id for this delivery, or a hash of the body when the carrier sends none. */
    String deliveryId();

    TrackingNumber trackingNumber();

    /** New events for a parcel. */
    record Updated(String deliveryId, TrackingSnapshot snapshot) implements WebhookNotification {
        public Updated {
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public TrackingNumber trackingNumber() {
            return snapshot.trackingNumber();
        }
    }

    /** The carrier stopped tracking the parcel (for example, it expired); we fall back to polling. */
    record Stopped(String deliveryId, TrackingNumber trackingNumber) implements WebhookNotification {
        public Stopped {
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(trackingNumber, "trackingNumber");
        }
    }
}
