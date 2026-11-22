package io.github.joshde3.trackhub.app.persistence;

import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.IN_TRANSIT;
import static io.github.joshde3.trackhub.domain.shipment.ShipmentStatus.OUT_FOR_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joshde3.trackhub.app.PostgresTestConfiguration;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository.ConcurrentUpdateException;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository.DuplicateShipmentException;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs against a real Postgres with the real Flyway migrations, so the unique constraints and the
 * optimistic lock are tested for real rather than assumed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class JpaShipmentRepositoryTest {

    private static final CarrierCode TRACK17 = new CarrierCode("track17");
    private static final TrackingNumber NUMBER = TrackingNumber.of("RR123456789IN");

    @Autowired
    private ShipmentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID owner;

    @BeforeEach
    void createUser() {
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, google_sub, email) VALUES (?, ?, ?)",
                owner, "sub-" + owner, owner + "@example.com");
    }

    private static TrackingEvent event(String at, String raw, io.github.joshde3.trackhub.domain.shipment.ShipmentStatus status) {
        return new TrackingEvent(Instant.parse(at), raw, status, "Pune", "scan " + raw);
    }

    @Test
    void savesAndLoadsAShipmentWithItsTimeline() {
        Shipment shipment = Shipment.register(owner, TRACK17, NUMBER, Clock.systemUTC());
        shipment.applyEvents(List.of(
                event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT),
                event("2026-11-21T08:00:00Z", "OFD", OUT_FOR_DELIVERY)));

        Shipment saved = repository.save(shipment);
        Shipment loaded = repository.findForOwner(shipment.id(), owner).orElseThrow();

        assertThat(saved.version()).contains(0L);
        assertThat(loaded.status()).isEqualTo(OUT_FOR_DELIVERY);
        assertThat(loaded.statusEventAt()).contains(Instant.parse("2026-11-21T08:00:00Z"));
        assertThat(loaded.events()).extracting(TrackingEvent::rawStatus).containsExactly("IT", "OFD");
        assertThat(repository.findByTracking(owner, TRACK17, NUMBER)).isPresent();
    }

    @Test
    void anotherUserCannotLoadTheShipment() {
        Shipment shipment = repository.save(Shipment.register(owner, TRACK17, NUMBER, Clock.systemUTC()));

        assertThat(repository.findForOwner(shipment.id(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void appendsOnlyNewEventsOnUpdate() {
        Shipment saved = repository.save(Shipment.register(owner, TRACK17, NUMBER, Clock.systemUTC()));
        saved.applyEvents(List.of(event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT)));
        saved = repository.save(saved);

        saved.applyEvents(List.of(event("2026-11-21T08:00:00Z", "OFD", OUT_FOR_DELIVERY)));
        Shipment updated = repository.save(saved);

        assertThat(updated.events()).hasSize(2);
        assertThat(updated.version()).contains(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tracking_events WHERE shipment_id = ?", Long.class, saved.id()))
                .isEqualTo(2L);
    }

    @Test
    void rejectsTrackingTheSameParcelTwice() {
        repository.save(Shipment.register(owner, TRACK17, NUMBER, Clock.systemUTC()));

        assertThatThrownBy(() -> repository.save(Shipment.register(owner, TRACK17, NUMBER, Clock.systemUTC())))
                .isInstanceOf(DuplicateShipmentException.class);
    }

    @Test
    void twoUsersMayTrackTheSameParcel() {
        UUID otherOwner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, google_sub, email) VALUES (?, ?, ?)",
                otherOwner, "sub-" + otherOwner, otherOwner + "@example.com");

        repository.save(Shipment.register(owner, TRACK17, NUMBER, Clock.systemUTC()));
        repository.save(Shipment.register(otherOwner, TRACK17, NUMBER, Clock.systemUTC()));

        assertThat(repository.findByTracking(otherOwner, TRACK17, NUMBER)).isPresent();
    }

    @Test
    void findsEveryUsersCopyOfAParcelForWebhooks() {
        UUID otherOwner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, google_sub, email) VALUES (?, ?, ?)",
                otherOwner, "sub-" + otherOwner, otherOwner + "@example.com");
        TrackingNumber shared = TrackingNumber.of("CP" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() + "CN");
        repository.save(Shipment.register(owner, TRACK17, shared, Clock.systemUTC()));
        repository.save(Shipment.register(otherOwner, TRACK17, shared, Clock.systemUTC()));

        assertThat(repository.findAllTracking(TRACK17, shared)).extracting(Shipment::ownerId)
                .containsExactlyInAnyOrder(owner, otherOwner);
    }

    @Test
    void detectsALostUpdate() {
        Shipment saved = repository.save(Shipment.register(owner, TRACK17, NUMBER, Clock.systemUTC()));
        // Two requests load the same version...
        Shipment webhookCopy = repository.findForOwner(saved.id(), owner).orElseThrow();
        Shipment refreshCopy = repository.findForOwner(saved.id(), owner).orElseThrow();

        // ...the first one saves...
        webhookCopy.applyEvents(List.of(event("2026-11-20T12:00:00Z", "IT", IN_TRANSIT)));
        repository.save(webhookCopy);

        // ...and the second one must not silently overwrite it.
        refreshCopy.applyEvents(List.of(event("2026-11-21T08:00:00Z", "OFD", OUT_FOR_DELIVERY)));
        assertThatThrownBy(() -> repository.save(refreshCopy))
                .isInstanceOf(ConcurrentUpdateException.class);
    }
}
