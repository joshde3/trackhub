package io.github.joshde3.trackhub.app.shipment;

import java.util.UUID;

/** No such shipment for this user. Also what another user's shipment looks like: we don't say it exists. */
public final class ShipmentNotFoundException extends RuntimeException {
    public ShipmentNotFoundException(UUID id) {
        super("No shipment " + id);
    }
}
