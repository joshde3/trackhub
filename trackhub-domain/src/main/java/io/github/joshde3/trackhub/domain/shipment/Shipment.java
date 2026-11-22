package io.github.joshde3.trackhub.domain.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A parcel one user is tracking: the aggregate root of the domain.
 *
 * <p>Nothing outside this class changes a shipment's status. Carrier data comes in through
 * {@link #applyEvents(List)}, which enforces the state machine in {@link ShipmentStatus} and the
 * merge rules below, so every way an update can arrive (webhook, poll, manual refresh) follows the
 * same rules.
 */
public final class Shipment {

    private final UUID id;
    private final UUID ownerId;
    private final CarrierCode carrier;
    private final TrackingNumber trackingNumber;
    private final Instant createdAt;

    private ShipmentStatus status;
    private Instant statusChangedAt;
    /** occurredAt of the carrier event that set the current status; null while still CREATED by us. */
    private Instant statusEventAt;

    private final List<TrackingEvent> events = new ArrayList<>();
    private final Set<String> eventHashes = new HashSet<>();

    private Shipment(
            UUID id,
            UUID ownerId,
            CarrierCode carrier,
            TrackingNumber trackingNumber,
            Instant createdAt,
            ShipmentStatus status,
            Instant statusChangedAt,
            Instant statusEventAt,
            List<TrackingEvent> events) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.carrier = Objects.requireNonNull(carrier, "carrier");
        this.trackingNumber = Objects.requireNonNull(trackingNumber, "trackingNumber");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = Objects.requireNonNull(status, "status");
        this.statusChangedAt = Objects.requireNonNull(statusChangedAt, "statusChangedAt");
        this.statusEventAt = statusEventAt;
        for (TrackingEvent event : events) {
            if (eventHashes.add(event.dedupeHash())) {
                this.events.add(event);
            }
        }
        this.events.sort(Comparator.comparing(TrackingEvent::occurredAt));
    }

    /** A parcel the user has just added. Its status is CREATED until the carrier reports otherwise. */
    public static Shipment register(
            UUID ownerId, CarrierCode carrier, TrackingNumber trackingNumber, Clock clock) {
        Instant now = clock.instant();
        return new Shipment(
                UUID.randomUUID(), ownerId, carrier, trackingNumber, now,
                ShipmentStatus.CREATED, now, null, List.of());
    }

    /** Rebuilds a shipment from storage. Used by the persistence adapter only. */
    public static Shipment restore(
            UUID id,
            UUID ownerId,
            CarrierCode carrier,
            TrackingNumber trackingNumber,
            Instant createdAt,
            ShipmentStatus status,
            Instant statusChangedAt,
            Instant statusEventAt,
            List<TrackingEvent> events) {
        return new Shipment(id, ownerId, carrier, trackingNumber, createdAt,
                status, statusChangedAt, statusEventAt, events);
    }

    /**
     * Merges carrier events into this shipment and moves its status if the rules allow.
     *
     * <ol>
     *   <li>Events already on the timeline (same {@link TrackingEvent#dedupeHash()}) are ignored,
     *       so a webhook and a poll reporting the same scan collapse into one.</li>
     *   <li>New events are processed oldest first, because carriers deliver late and out of
     *       order.</li>
     *   <li>An event moves the status only if {@link ShipmentStatus#canMoveTo} allows it and it is
     *       not older than the event that set the current status: late news never overrides newer
     *       news.</li>
     *   <li>Every new event joins the timeline, even one that moves nothing, and so does an event
     *       whose carrier status we could not map.</li>
     * </ol>
     *
     * @return the change, if the status ended up different from before; empty otherwise
     */
    public Optional<StatusChange> applyEvents(List<TrackingEvent> incoming) {
        Objects.requireNonNull(incoming, "incoming");
        ShipmentStatus before = status;

        List<TrackingEvent> fresh = incoming.stream()
                .filter(event -> eventHashes.add(event.dedupeHash()))
                .sorted(Comparator.comparing(TrackingEvent::occurredAt))
                .toList();

        for (TrackingEvent event : fresh) {
            events.add(event);
            event.status()
                    .filter(target -> isNotOlderThanCurrentStatus(event))
                    .filter(status::canMoveTo)
                    .ifPresent(target -> moveTo(target, event.occurredAt()));
        }
        events.sort(Comparator.comparing(TrackingEvent::occurredAt));

        return status == before
                ? Optional.empty()
                : Optional.of(new StatusChange(before, status, statusChangedAt));
    }

    private boolean isNotOlderThanCurrentStatus(TrackingEvent event) {
        return statusEventAt == null || !event.occurredAt().isBefore(statusEventAt);
    }

    private void moveTo(ShipmentStatus target, Instant at) {
        status = target;
        statusChangedAt = at;
        statusEventAt = at;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public CarrierCode carrier() {
        return carrier;
    }

    public TrackingNumber trackingNumber() {
        return trackingNumber;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ShipmentStatus status() {
        return status;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    public Optional<Instant> statusEventAt() {
        return Optional.ofNullable(statusEventAt);
    }

    /** The timeline, oldest first. */
    public List<TrackingEvent> events() {
        return Collections.unmodifiableList(events);
    }
}
