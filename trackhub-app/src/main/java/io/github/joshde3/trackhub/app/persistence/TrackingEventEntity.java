package io.github.joshde3.trackhub.app.persistence;

import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA mapping of one row of a parcel's timeline. Events are append-only: never updated. */
@Entity
@Table(name = "tracking_events")
class TrackingEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    ShipmentEntity shipment;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    Instant occurredAt;

    @Column(name = "raw_status", nullable = false, updatable = false)
    String rawStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_status", updatable = false)
    ShipmentStatus normalizedStatus;

    @Column(updatable = false)
    String location;

    @Column(updatable = false)
    String description;

    @Column(name = "dedupe_hash", nullable = false, updatable = false)
    String dedupeHash;

    protected TrackingEventEntity() {
        // for JPA
    }
}
