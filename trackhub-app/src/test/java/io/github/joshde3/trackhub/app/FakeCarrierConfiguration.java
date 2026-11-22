package io.github.joshde3.trackhub.app;

import io.github.joshde3.trackhub.carriers.CarrierRegistry;
import io.github.joshde3.trackhub.carriers.track17.Track17Adapter;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real registry so API tests choose what the carrier answers. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeCarrierConfiguration {

    @Bean
    FakeCarrier fakeCarrier() {
        return new FakeCarrier();
    }

    @Bean
    @Primary
    CarrierRegistry fakeCarrierRegistry(FakeCarrier fake, Track17Adapter track17) {
        // The fake is "direct" and recognises everything, so it always wins detection; 17TRACK
        // stays registered (pointing nowhere) so the stored-code lookup paths are realistic.
        return new CarrierRegistry(List.of(fake), track17);
    }
}
