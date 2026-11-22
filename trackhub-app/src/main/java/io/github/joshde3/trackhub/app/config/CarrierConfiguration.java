package io.github.joshde3.trackhub.app.config;

import io.github.joshde3.trackhub.carriers.CarrierRegistry;
import io.github.joshde3.trackhub.carriers.track17.Track17Adapter;
import io.github.joshde3.trackhub.carriers.track17.Track17Settings;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the plain-Java carrier adapters into Spring. The adapters themselves don't know Spring
 * exists (ADR 0003); this class is the only place that does.
 */
@Configuration(proxyBeanMethods = false)
class CarrierConfiguration {

    @Bean
    Track17Adapter track17Adapter(
            @Value("${trackhub.track17.base-url}") URI baseUrl,
            @Value("${trackhub.track17.api-key}") String apiKey,
            @Value("${trackhub.track17.timeout}") Duration timeout) {
        // Fails at startup with "TRACK17_API_KEY is not set" rather than on the first request.
        return new Track17Adapter(new Track17Settings(baseUrl, apiKey, timeout));
    }

    @Bean
    CarrierRegistry carrierRegistry(Track17Adapter track17) {
        // Delhivery and India Post join the first list when we have their API access.
        return new CarrierRegistry(List.of(), track17);
    }

    /** One clock for the whole app, so tests can fix time instead of sleeping. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
