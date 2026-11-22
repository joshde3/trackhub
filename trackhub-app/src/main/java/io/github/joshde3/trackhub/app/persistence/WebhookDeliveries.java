package io.github.joshde3.trackhub.app.persistence;

import io.github.joshde3.trackhub.domain.port.WebhookNotification;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The idempotency record for carrier webhooks (ADR 0005). */
@Component
public class WebhookDeliveries {

    private final JdbcTemplate jdbc;

    WebhookDeliveries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the delivery and returns {@code true} if it is the first time we see it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching the unique violation: in Postgres a
     * failed statement aborts the whole transaction, so after a caught violation we couldn't even
     * answer cleanly. If two copies arrive at once, the second INSERT waits for the first
     * transaction: on commit it does nothing (duplicate), on rollback it inserts and does the work.
     * Must run inside the caller's transaction.
     */
    public boolean recordFirst(CarrierCode carrier, WebhookNotification notification) {
        String kind = switch (notification) {
            case WebhookNotification.Updated u -> "UPDATED";
            case WebhookNotification.Stopped s -> "STOPPED";
        };
        int inserted = jdbc.update("""
                INSERT INTO webhook_deliveries (carrier_code, external_event_id, tracking_number, kind)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (carrier_code, external_event_id) DO NOTHING
                """, carrier.value(), notification.deliveryId(), notification.trackingNumber().value(), kind);
        return inserted == 1;
    }
}
