package io.github.joshde3.trackhub.app.security;

import io.github.joshde3.trackhub.carriers.CarrierRegistry;
import io.github.joshde3.trackhub.carriers.track17.Track17Adapter;
import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Load-test only, in test sources so it can never ship. Tokens are signed by the k6 script with a
 * shared key instead of by Google, but go through the same claim checks (issuer, audience,
 * verified email). The carrier answers after a fixed delay, like a real API.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LoadTestConfiguration {

    /** Must match loadtest/tracking.js. Deliberately low-entropy: it guards nothing real. */
    static final String SIGNING_KEY = "load-test-only-load-test-only-load-test-only";
    static final long CARRIER_LATENCY_MS = 300;

    @Bean
    @Primary
    JwtDecoder loadTestJwtDecoder(@Value("${trackhub.google.client-id}") String clientId) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new GoogleIdTokenValidator(clientId)));
        return decoder;
    }

    @Bean
    @Primary
    CarrierRegistry loadTestCarriers(Track17Adapter track17) {
        return new CarrierRegistry(List.of(new SlowCarrier()), track17);
    }

    /** Every parcel is in transit with three scans; each call takes {@value #CARRIER_LATENCY_MS} ms. */
    static final class SlowCarrier implements CarrierAdapter {
        @Override public CarrierCode code() { return new CarrierCode("slow"); }
        @Override public boolean recognises(TrackingNumber number) { return true; }
        @Override public Optional<ShipmentStatus> mapStatus(String rawStatus) { return Optional.empty(); }

        @Override
        public TrackingSnapshot track(TrackingNumber number) {
            try {
                Thread.sleep(CARRIER_LATENCY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new TrackingSnapshot(number, null, List.of(
                    new TrackingEvent(Instant.parse("2026-11-19T05:30:00Z"), "PickedUp", ShipmentStatus.IN_TRANSIT, "Mumbai GPO", "Item booked"),
                    new TrackingEvent(Instant.parse("2026-11-20T12:40:00Z"), "Departure", ShipmentStatus.IN_TRANSIT, "Mumbai NSH", "Item dispatched"),
                    new TrackingEvent(Instant.parse("2026-11-21T04:00:00Z"), "Arrival", ShipmentStatus.IN_TRANSIT, "Pune City HO", "Item received")));
        }
    }
}
