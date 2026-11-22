package io.github.joshde3.trackhub.app.persistence;

import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of the {@code shipments} table. Lives in the persistence adapter only: the domain's
 * {@code Shipment} never sees it (ADR 0002). Fields are package-private and mutated only by
 * {@link ShipmentMapper}.
 */
@Entity
@Table(name = "shipments")
class ShipmentEntity {

    @Id
    UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    UUID userId;

    @Column(name = "carrier_code", nullable = false, updatable = false)
    String carrierCode;

    @Column(name = "tracking_number", nullable = false, updatable = false)
    String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    ShipmentStatus status;

    @Column(name = "status_changed_at", nullable = false)
    Instant statusChangedAt;

    @Column(name = "status_event_at")
    Instant statusEventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    /** Null until first saved; Hibernate then manages it and checks it on every UPDATE. */
    @Version
    Long version;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("occurredAt ASC")
    List<TrackingEventEntity> events = new ArrayList<>();

    protected ShipmentEntity() {
        // for JPA
    }
}
