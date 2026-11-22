package io.github.joshde3.trackhub.domain.shipment;

import java.time.Instant;
import java.util.Objects;

/**
 * The result of applying carrier events when the status actually changed. Notifications are sent
 * for these, and only these.
 */
public record StatusChange(ShipmentStatus from, ShipmentStatus to, Instant occurredAt) {

    public StatusChange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (from == to) {
            throw new IllegalArgumentException("A status change needs two different statuses");
        }
    }
}
