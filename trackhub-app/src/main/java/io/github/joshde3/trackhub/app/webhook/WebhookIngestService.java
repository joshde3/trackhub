package io.github.joshde3.trackhub.app.webhook;

import io.github.joshde3.trackhub.app.persistence.WebhookDeliveries;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository.ConcurrentUpdateException;
import io.github.joshde3.trackhub.domain.port.WebhookNotification;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one verified webhook exactly once (ADR 0005).
 *
 * <p>Everything happens in one transaction: record the delivery, then update every user's copy
 * of the parcel. A duplicate stops at the first step. Any failure rolls back the delivery row as
 * well, we answer 5xx, and the carrier's retry starts from scratch, so there is never a delivery
 * marked done whose events are missing.
 */
@Service
public class WebhookIngestService {

    public enum Outcome { APPLIED, DUPLICATE }

    private static final Logger LOG = LoggerFactory.getLogger(WebhookIngestService.class);

    private final WebhookDeliveries deliveries;
    private final ShipmentRepository shipments;
    private final TransactionTemplate transaction;

    WebhookIngestService(WebhookDeliveries deliveries, ShipmentRepository shipments, TransactionTemplate transaction) {
        this.deliveries = deliveries;
        this.shipments = shipments;
        this.transaction = transaction;
    }

    public Outcome ingest(CarrierCode carrier, WebhookNotification notification) {
        try {
            return transaction.execute(status -> ingestOnce(carrier, notification));
        } catch (ConcurrentUpdateException e) {
            // A user's refresh saved the same parcel mid-way. The whole attempt rolled back,
            // delivery row included, so a second attempt on fresh data is safe. No carrier call
            // happens in here, so the retry is cheap.
            LOG.info("Webhook {} for {} raced another update; retrying once", notification.deliveryId(), notification.trackingNumber());
            return transaction.execute(status -> ingestOnce(carrier, notification));
        }
    }

    private Outcome ingestOnce(CarrierCode carrier, WebhookNotification notification) {
        if (!deliveries.recordFirst(carrier, notification)) {
            LOG.info("Duplicate webhook {} from {} ignored", notification.deliveryId(), carrier);
            return Outcome.DUPLICATE;
        }
        switch (notification) {
            case WebhookNotification.Updated updated -> {
                List<Shipment> copies = shipments.findAllTracking(carrier, updated.trackingNumber());
                for (Shipment shipment : copies) {
                    shipment.applyEvents(updated.snapshot().events());
                    shipments.save(shipment);
                }
                LOG.info("Webhook {} updated {} shipment(s) for {}", notification.deliveryId(), copies.size(), updated.trackingNumber());
            }
            // Polling takes over for stopped parcels; the poller arrives in Phase 2.
            case WebhookNotification.Stopped stopped ->
                    LOG.info("{} stopped pushing updates for {}", carrier, stopped.trackingNumber());
        }
        return Outcome.APPLIED;
    }
}
