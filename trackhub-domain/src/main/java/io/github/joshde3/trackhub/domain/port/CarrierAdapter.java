package io.github.joshde3.trackhub.domain.port;

import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.util.Optional;

/**
 * Port for asking one carrier (or aggregator) where a parcel is. Each carrier has one adapter in
 * {@code trackhub-carriers}; the domain never knows which HTTP client or JSON library it uses
 * (ADR 0003).
 *
 * <p>Adapters only translate. They never decide whether a status change is allowed or whether an
 * event is a duplicate: that is {@code Shipment.applyEvents}, so every carrier gets the same rules.
 */
public interface CarrierAdapter {

    /** Stable code stored with each shipment, for example {@code track17}. */
    CarrierCode code();

    /**
     * Whether this adapter can track numbers shaped like this one. Used to pick a carrier when the
     * user doesn't name one. An aggregator returns {@code true} for everything.
     */
    boolean recognises(TrackingNumber number);

    /**
     * Fetches the parcel's full timeline as the carrier knows it right now.
     *
     * @throws TrackingNotFoundException if the carrier has no such parcel
     * @throws CarrierUnavailableException if the carrier is down, slow, or rate limiting us
     */
    TrackingSnapshot track(TrackingNumber number);

    /**
     * Translates one of this carrier's status codes to the unified status, or empty when we don't
     * know the code. Unknown codes are kept on the timeline but never move the shipment, so a new
     * carrier status can't corrupt our state; we notice it in the logs and add a mapping.
     */
    Optional<ShipmentStatus> mapStatus(String rawStatus);
}
