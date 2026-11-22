package io.github.joshde3.trackhub.app.persistence;

import io.github.joshde3.trackhub.domain.port.ShipmentRepository;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter that implements the domain's {@link ShipmentRepository} port with Spring Data JPA.
 *
 * <p>Concurrency is handled with optimistic locking: no rows are locked while a carrier is being
 * called. Two checks catch a lost update:
 * <ol>
 *   <li>Here, before writing: the version the domain object was loaded with must still be the
 *       stored version, otherwise someone saved in between.</li>
 *   <li>In the database, while writing: Hibernate's UPDATE says {@code WHERE version = ?}, which
 *       catches a save that lands between our check and our write.</li>
 * </ol>
 */
@Repository
@Transactional
class JpaShipmentRepository implements ShipmentRepository {

    private static final String UNIQUE_OWNER_TRACKING = "uq_shipments_owner_tracking";

    private final ShipmentJpaRepository jpa;

    JpaShipmentRepository(ShipmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Shipment save(Shipment shipment) {
        try {
            ShipmentEntity entity = shipment.version().isEmpty()
                    ? ShipmentMapper.newEntity(shipment)
                    : updatedEntity(shipment);
            return ShipmentMapper.toDomain(jpa.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            if (violated(e, UNIQUE_OWNER_TRACKING)) {
                throw new DuplicateShipmentException(shipment.carrier(), shipment.trackingNumber());
            }
            throw e;
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentUpdateException(shipment.id(), e);
        }
    }

    private ShipmentEntity updatedEntity(Shipment shipment) {
        ShipmentEntity entity = jpa.findById(shipment.id())
                .orElseThrow(() -> new IllegalStateException("Shipment " + shipment.id() + " no longer exists"));
        if (!Objects.equals(entity.version, shipment.version().orElseThrow())) {
            throw new ConcurrentUpdateException(shipment.id(), null);
        }
        ShipmentMapper.copyMutableState(shipment, entity);
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findForOwner(UUID shipmentId, UUID ownerId) {
        return jpa.findByIdAndUserId(shipmentId, ownerId).map(ShipmentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findByTracking(UUID ownerId, CarrierCode carrier, TrackingNumber trackingNumber) {
        return jpa.findByUserIdAndCarrierCodeAndTrackingNumber(ownerId, carrier.value(), trackingNumber.value())
                .map(ShipmentMapper::toDomain);
    }

    private static boolean violated(DataIntegrityViolationException e, String constraint) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(constraint);
    }
}
