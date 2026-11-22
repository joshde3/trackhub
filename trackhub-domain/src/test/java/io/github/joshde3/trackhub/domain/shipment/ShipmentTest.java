package io.github.joshde3.trackhub.domain.shipment;

import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.CREATED;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.DELIVERED;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.EXCEPTION;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.IN_TRANSIT;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.OUT_FOR_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ShipmentTest {

    private static final Instant ADDED_AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(ADDED_AT, ZoneOffset.UTC);

    private final Shipment shipment = Shipment.register(
            UUID.randomUUID(), new CarrierCode("track17"), TrackingNumber.of("RR123456789IN"), CLOCK);

    private static TrackingEvent event(String at, ShipmentStatus status) {
        return new TrackingEvent(Instant.parse(at), "raw-" + status, status, "Pune", null);
    }

    @Nested
    class Registering {

        @Test
        void startsAsCreatedWithAnEmptyTimeline() {
            assertThat(shipment.status()).isEqualTo(CREATED);
            assertThat(shipment.statusChangedAt()).isEqualTo(ADDED_AT);
            assertThat(shipment.createdAt()).isEqualTo(ADDED_AT);
            assertThat(shipment.statusEventAt()).isEmpty();
            assertThat(shipment.events()).isEmpty();
        }
    }

    @Nested
    class ApplyingEvents {

        @Test
        void movesForwardAndReportsTheChange() {
            var change = shipment.applyEvents(List.of(event("2026-11-20T12:00:00Z", IN_TRANSIT)));

            assertThat(change).contains(
                    new StatusChange(CREATED, IN_TRANSIT, Instant.parse("2026-11-20T12:00:00Z")));
            assertThat(shipment.status()).isEqualTo(IN_TRANSIT);
        }

        @Test
        void acceptsEventsFromBeforeTheParcelWasAdded() {
            // A user often adds a parcel days after it shipped.
            shipment.applyEvents(List.of(event("2026-11-18T09:00:00Z", IN_TRANSIT)));

            assertThat(shipment.status()).isEqualTo(IN_TRANSIT);
        }

        @Test
        void allowsForwardJumpsWhenTheCarrierSkipsAStep() {
            shipment.applyEvents(List.of(event("2026-11-20T12:00:00Z", DELIVERED)));

            assertThat(shipment.status()).isEqualTo(DELIVERED);
        }

        @Test
        void processesABatchOldestFirstWhateverOrderItArrivesIn() {
            var change = shipment.applyEvents(List.of(
                    event("2026-11-21T15:00:00Z", DELIVERED),
                    event("2026-11-20T12:00:00Z", IN_TRANSIT),
                    event("2026-11-21T08:00:00Z", OUT_FOR_DELIVERY)));

            assertThat(change).contains(
                    new StatusChange(CREATED, DELIVERED, Instant.parse("2026-11-21T15:00:00Z")));
            assertThat(shipment.events()).extracting(TrackingEvent::normalizedStatus)
                    .containsExactly(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED);
        }

        @Test
        void lateEventNeverOverridesNewerNews() {
            shipment.applyEvents(List.of(event("2026-11-21T08:00:00Z", OUT_FOR_DELIVERY)));

            // An older EXCEPTION arrives late: it is allowed by the state machine but is stale.
            var change = shipment.applyEvents(List.of(event("2026-11-20T12:00:00Z", EXCEPTION)));

            assertThat(change).isEmpty();
            assertThat(shipment.status()).isEqualTo(OUT_FOR_DELIVERY);
            assertThat(shipment.events()).hasSize(2);
        }

        @Test
        void neverMovesBackwards() {
            shipment.applyEvents(List.of(event("2026-11-21T08:00:00Z", OUT_FOR_DELIVERY)));

            var change = shipment.applyEvents(List.of(event("2026-11-21T09:00:00Z", IN_TRANSIT)));

            assertThat(change).isEmpty();
            assertThat(shipment.status()).isEqualTo(OUT_FOR_DELIVERY);
        }

        @Test
        void recoversFromAnException() {
            shipment.applyEvents(List.of(event("2026-11-20T12:00:00Z", EXCEPTION)));

            var change = shipment.applyEvents(List.of(event("2026-11-21T09:00:00Z", IN_TRANSIT)));

            assertThat(change).contains(
                    new StatusChange(EXCEPTION, IN_TRANSIT, Instant.parse("2026-11-21T09:00:00Z")));
        }

        @Test
        void nothingMovesADeliveredParcel() {
            shipment.applyEvents(List.of(event("2026-11-21T15:00:00Z", DELIVERED)));

            var change = shipment.applyEvents(List.of(event("2026-11-22T09:00:00Z", EXCEPTION)));

            assertThat(change).isEmpty();
            assertThat(shipment.status()).isEqualTo(DELIVERED);
        }

        @Test
        void keepsUnmappedEventsOnTheTimelineWithoutChangingStatus() {
            var unknown = new TrackingEvent(
                    Instant.parse("2026-11-20T12:00:00Z"), "XYZ-42", null, null, "Bagging done");

            var change = shipment.applyEvents(List.of(unknown));

            assertThat(change).isEmpty();
            assertThat(shipment.status()).isEqualTo(CREATED);
            assertThat(shipment.events()).containsExactly(unknown);
        }

        @Test
        void reportsOnlyTheNetChangeOfABatch() {
            // EXCEPTION then resolved back to IN_TRANSIT within one batch: one change, CREATED -> IN_TRANSIT.
            var change = shipment.applyEvents(List.of(
                    event("2026-11-20T12:00:00Z", EXCEPTION),
                    event("2026-11-20T18:00:00Z", IN_TRANSIT)));

            assertThat(change).contains(
                    new StatusChange(CREATED, IN_TRANSIT, Instant.parse("2026-11-20T18:00:00Z")));
        }
    }

    @Nested
    class Deduplication {

        @Test
        void sameScanFromWebhookAndPollLandsOnce() {
            var scan = event("2026-11-20T12:00:00Z", IN_TRANSIT);
            shipment.applyEvents(List.of(scan));

            var change = shipment.applyEvents(List.of(scan));

            assertThat(change).isEmpty();
            assertThat(shipment.events()).hasSize(1);
        }

        @Test
        void duplicatesInsideOneBatchLandOnce() {
            var scan = event("2026-11-20T12:00:00Z", IN_TRANSIT);

            shipment.applyEvents(List.of(scan, scan));

            assertThat(shipment.events()).hasSize(1);
        }

        @Test
        void carrierRewordingTheDescriptionIsStillTheSameScan() {
            Instant at = Instant.parse("2026-11-20T12:00:00Z");
            shipment.applyEvents(List.of(new TrackingEvent(at, "IT", IN_TRANSIT, "Pune", "In transit")));

            shipment.applyEvents(List.of(new TrackingEvent(at, "IT", IN_TRANSIT, "Pune", "Shipment in transit")));

            assertThat(shipment.events()).hasSize(1);
        }
    }

    @Nested
    class Restoring {

        @Test
        void rebuildsStateAndKeepsTheLateEventRule() {
            Instant ofdAt = Instant.parse("2026-11-21T08:00:00Z");
            var stored = List.of(
                    event("2026-11-21T08:00:00Z", OUT_FOR_DELIVERY),
                    event("2026-11-20T12:00:00Z", IN_TRANSIT));

            var restored = Shipment.restore(
                    shipment.id(), shipment.ownerId(), shipment.carrier(), shipment.trackingNumber(),
                    ADDED_AT, OUT_FOR_DELIVERY, ofdAt, ofdAt, stored);

            assertThat(restored.events()).extracting(TrackingEvent::normalizedStatus)
                    .containsExactly(IN_TRANSIT, OUT_FOR_DELIVERY);
            assertThat(restored.statusEventAt()).contains(ofdAt);
            assertThat(restored.applyEvents(List.of(event("2026-11-20T20:00:00Z", EXCEPTION)))).isEmpty();
        }

        @Test
        void timelineCannotBeModifiedFromOutside() {
            assertThatThrownBy(() -> shipment.events().add(event("2026-11-20T12:00:00Z", IN_TRANSIT)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
