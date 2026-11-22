package io.github.joshde3.trackhub.app.shipment;

import io.github.joshde3.trackhub.carriers.CarrierRegistry;
import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.CarrierUnavailableException;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository.ConcurrentUpdateException;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository.DuplicateShipmentException;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The use cases behind the shipment endpoints. Deliberately <em>not</em> {@code @Transactional}:
 * a carrier call can take seconds, and holding a database connection (we have five) or a row lock
 * for that long would starve everything else. We call the carrier first, then save in a short
 * transaction, and the version column catches anyone who saved in between (ADR 0011).
 */
@Service
public class ShipmentService {

    private static final Logger LOG = LoggerFactory.getLogger(ShipmentService.class);

    private final ShipmentRepository shipments;
    private final CarrierRegistry carriers;
    private final CarrierLookup lookup;
    private final Clock clock;

    ShipmentService(ShipmentRepository shipments, CarrierRegistry carriers, CarrierLookup lookup, Clock clock) {
        this.shipments = shipments;
        this.carriers = carriers;
        this.lookup = lookup;
        this.clock = clock;
    }

    /**
     * Starts tracking a parcel.
     *
     * <p>If the carrier doesn't know the number, nothing is saved (the user probably mistyped it).
     * If the carrier is only <em>down</em>, the parcel is saved without events and the poller fills
     * them in later: a carrier outage shouldn't stop someone adding a parcel.
     *
     * @param carrierCode the carrier the user picked, or {@code null} to detect it
     */
    public Shipment add(UUID ownerId, String rawNumber, String carrierCode) {
        TrackingNumber number = TrackingNumber.of(rawNumber);
        CarrierAdapter adapter = carrierCode == null ? carriers.detect(number) : adapterFor(carrierCode);
        // Cheap early answer; the unique constraint still decides if two adds race.
        if (shipments.findByTracking(ownerId, adapter.code(), number).isPresent()) {
            throw new DuplicateShipmentException(adapter.code(), number);
        }

        Shipment shipment = Shipment.register(ownerId, adapter.code(), number, clock);
        try {
            shipment.applyEvents(lookup.track(adapter, number).events());
        } catch (CarrierUnavailableException e) {
            LOG.warn("Carrier {} unavailable while adding {}; saving without events: {}", adapter.code(), number, e.getMessage());
        }
        return shipments.save(shipment);
    }

    public Shipment get(UUID ownerId, UUID shipmentId) {
        return shipments.findForOwner(shipmentId, ownerId).orElseThrow(() -> new ShipmentNotFoundException(shipmentId));
    }

    /**
     * Asks the carrier for news now. If a webhook saved the same shipment while we were waiting on
     * the carrier, our save loses the optimistic-lock race; we reload and apply our events on top
     * once. The events are the same either way, and dedupe makes re-applying them harmless.
     */
    public Shipment refresh(UUID ownerId, UUID shipmentId) {
        Shipment shipment = get(ownerId, shipmentId);
        CarrierAdapter adapter = carriers.byCode(shipment.carrier())
                .orElseThrow(() -> new UnknownCarrierException(shipment.carrier().value()));
        // Within the TTL this is served from the cache: a user tapping refresh repeatedly costs no quota.
        TrackingSnapshot snapshot = lookup.track(adapter, shipment.trackingNumber());
        shipment.applyEvents(snapshot.events());
        try {
            return shipments.save(shipment);
        } catch (ConcurrentUpdateException e) {
            LOG.info("Shipment {} changed during refresh; retrying on the latest version", shipmentId);
            Shipment latest = get(ownerId, shipmentId);
            latest.applyEvents(snapshot.events());
            return shipments.save(latest);
        }
    }

    private CarrierAdapter adapterFor(String code) {
        try {
            return carriers.byCode(new CarrierCode(code)).orElseThrow(() -> new UnknownCarrierException(code));
        } catch (IllegalArgumentException e) {
            throw new UnknownCarrierException(code);
        }
    }
}
