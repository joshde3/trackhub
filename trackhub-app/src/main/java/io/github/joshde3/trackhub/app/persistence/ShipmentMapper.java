package io.github.joshde3.trackhub.app.persistence;

import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates between the domain's {@link Shipment} and the JPA entities. This is the price of a
 * framework-free domain (ADR 0002): a little mapping code in exchange for business rules that
 * know nothing about Hibernate.
 */
final class ShipmentMapper {

    private ShipmentMapper() {
    }

    static Shipment toDomain(ShipmentEntity entity) {
        return Shipment.restore(
                entity.id,
                entity.userId,
                new CarrierCode(entity.carrierCode),
                new TrackingNumber(entity.trackingNumber),
                entity.createdAt,
                entity.version,
                entity.status,
                entity.statusChangedAt,
                entity.statusEventAt,
                entity.events.stream().map(ShipmentMapper::toDomain).toList());
    }

    static ShipmentEntity newEntity(Shipment shipment) {
        ShipmentEntity entity = new ShipmentEntity();
        entity.id = shipment.id();
        entity.userId = shipment.ownerId();
        entity.carrierCode = shipment.carrier().value();
        entity.trackingNumber = shipment.trackingNumber().value();
        entity.createdAt = shipment.createdAt();
        copyMutableState(shipment, entity);
        return entity;
    }

    /**
     * Copies status fields and appends timeline events the entity doesn't have yet. Existing event
     * rows are never touched, so saving a parcel with a long timeline is one small INSERT per new
     * scan rather than a rewrite of the whole timeline.
     */
    static void copyMutableState(Shipment shipment, ShipmentEntity entity) {
        entity.status = shipment.status();
        entity.statusChangedAt = shipment.statusChangedAt();
        entity.statusEventAt = shipment.statusEventAt().orElse(null);

        Set<String> stored = entity.events.stream()
                .map(event -> event.dedupeHash)
                .collect(Collectors.toSet());
        for (TrackingEvent event : shipment.events()) {
            if (!stored.contains(event.dedupeHash())) {
                entity.events.add(toEntity(event, entity));
            }
        }
    }

    private static TrackingEvent toDomain(TrackingEventEntity entity) {
        return new TrackingEvent(
                entity.occurredAt, entity.rawStatus, entity.normalizedStatus,
                entity.location, entity.description);
    }

    private static TrackingEventEntity toEntity(TrackingEvent event, ShipmentEntity owner) {
        TrackingEventEntity entity = new TrackingEventEntity();
        entity.shipment = owner;
        entity.occurredAt = event.occurredAt();
        entity.rawStatus = event.rawStatus();
        entity.normalizedStatus = event.normalizedStatus();
        entity.location = event.location();
        entity.description = event.description();
        entity.dedupeHash = event.dedupeHash();
        return entity;
    }
}
