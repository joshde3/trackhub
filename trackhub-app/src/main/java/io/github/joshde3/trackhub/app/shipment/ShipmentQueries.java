package io.github.joshde3.trackhub.app.shipment;

import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read side for the dashboard list. It skips the aggregate on purpose: building full
 * {@code Shipment}s with their timelines to show one line each would load every event of every
 * parcel. One SQL query on {@code ix_shipments_user_created} is enough. Writes still always go
 * through {@code Shipment}, where the rules are.
 */
@Component
public class ShipmentQueries {

    public record Summary(
            UUID id, String carrier, String trackingNumber, ShipmentStatus status, Instant statusChangedAt, Instant createdAt) {}

    /** One page, plus whether another page exists. No {@code count(*)}: the app only needs "load more". */
    public record Page(List<Summary> items, boolean hasMore) {}

    private final JdbcTemplate jdbc;

    ShipmentQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page newestFirst(UUID ownerId, int page, int size) {
        // Ask for one extra row to learn whether there is a next page.
        List<Summary> rows = jdbc.query("""
                SELECT id, carrier_code, tracking_number, status, status_changed_at, created_at
                FROM shipments
                WHERE user_id = ? AND archived_at IS NULL
                ORDER BY created_at DESC, id
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> new Summary(
                        rs.getObject("id", UUID.class),
                        rs.getString("carrier_code"),
                        rs.getString("tracking_number"),
                        ShipmentStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("status_changed_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()),
                ownerId, size + 1, (long) page * size);
        boolean hasMore = rows.size() > size;
        return new Page(hasMore ? rows.subList(0, size) : rows, hasMore);
    }
}
