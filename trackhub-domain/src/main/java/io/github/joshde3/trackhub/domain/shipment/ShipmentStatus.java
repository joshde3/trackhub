package io.github.joshde3.trackhub.domain.shipment;

import java.util.EnumSet;
import java.util.Set;

/**
 * The unified status every carrier's own status is mapped to (ADR 0004).
 *
 * <p>Allowed moves:
 * <ul>
 *   <li>Forward along the happy path, including jumps: a carrier may report DELIVERED without ever
 *       reporting OUT_FOR_DELIVERY.</li>
 *   <li>Any non-terminal status to EXCEPTION or RETURNED.</li>
 *   <li>EXCEPTION back onto the happy path, once the problem is resolved. This is the only way a
 *       parcel moves "backwards".</li>
 *   <li>DELIVERED and RETURNED are terminal: nothing moves them.</li>
 * </ul>
 */
public enum ShipmentStatus {
    CREATED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    EXCEPTION,
    RETURNED;

    public boolean isTerminal() {
        return this == DELIVERED || this == RETURNED;
    }

    /** Whether a parcel in this status may change to {@code target}. Staying put is not a move. */
    public boolean canMoveTo(ShipmentStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<ShipmentStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, RETURNED);
            case IN_TRANSIT -> EnumSet.of(OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, RETURNED);
            case OUT_FOR_DELIVERY -> EnumSet.of(DELIVERED, EXCEPTION, RETURNED);
            case EXCEPTION -> EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RETURNED);
            case DELIVERED, RETURNED -> EnumSet.noneOf(ShipmentStatus.class);
        };
    }
}
