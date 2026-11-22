package io.github.joshde3.trackhub.app.shipment;

import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.CarrierUnavailableException;
import io.github.joshde3.trackhub.domain.port.TrackingNotFoundException;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * The one way the app asks a carrier anything, so caching and metrics happen in one place.
 *
 * <p>Only successful answers are cached. A "not found" or an outage is not, so a fixed typo or a
 * recovered carrier is seen on the next try.
 */
@Component
public class CarrierLookup {

    private final MeterRegistry metrics;

    CarrierLookup(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Cacheable(cacheNames = TrackingCache.NAME, key = "#adapter.code().value() + ':' + #number.value()")
    public TrackingSnapshot track(CarrierAdapter adapter, TrackingNumber number) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "ok";
        try {
            return adapter.track(number);
        } catch (TrackingNotFoundException e) {
            outcome = "not_found";
            throw e;
        } catch (CarrierUnavailableException e) {
            outcome = e.retryAfter().isPresent() ? "rate_limited" : "unavailable";
            throw e;
        } finally {
            // Counts real carrier calls only: a cache hit never reaches this method body.
            sample.stop(Timer.builder("trackhub.carrier.calls")
                    .description("Calls to carrier APIs")
                    .tag("carrier", adapter.code().value())
                    .tag("outcome", outcome)
                    .register(metrics));
        }
    }

    /** A webhook brought a fresher answer than any poll: serve it until its TTL. */
    @CachePut(cacheNames = TrackingCache.NAME, key = "#carrier.value() + ':' + #snapshot.trackingNumber().value()")
    public TrackingSnapshot remember(CarrierCode carrier, TrackingSnapshot snapshot) {
        return snapshot;
    }
}
