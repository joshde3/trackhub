package io.github.joshde3.trackhub.domain.port;

import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for storing shipments. The domain defines what it needs; the Spring/JPA adapter in
 * {@code trackhub-app} implements it (ADR 0002).
 *
 * <p>Every lookup takes the owner, so one user can never load another user's parcel by guessing
 * an id: ownership is enforced at the query, not remembered by each caller.
 */
public interface ShipmentRepository {

    /**
     * Inserts or updates the shipment and returns it as stored (with its new version).
     *
     * @throws DuplicateShipmentException if the owner already tracks this number with this carrier
     * @throws ConcurrentUpdateException if someone else saved the shipment since it was loaded
     */
    Shipment save(Shipment shipment);

    Optional<Shipment> findForOwner(UUID shipmentId, UUID ownerId);

    Optional<Shipment> findByTracking(UUID ownerId, CarrierCode carrier, TrackingNumber trackingNumber);

    /** The owner already tracks this parcel. Maps to HTTP 409 at the API. */
    final class DuplicateShipmentException extends RuntimeException {
        public DuplicateShipmentException(CarrierCode carrier, TrackingNumber trackingNumber) {
            super("Already tracking " + trackingNumber + " with " + carrier);
        }
    }

    /** Lost an optimistic-lock race; the caller reloads and retries once. */
    final class ConcurrentUpdateException extends RuntimeException {
        public ConcurrentUpdateException(UUID shipmentId, Throwable cause) {
            super("Shipment " + shipmentId + " was changed concurrently", cause);
        }
    }
}
