package io.github.joshde3.trackhub.carriers.track17;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Connection settings for 17TRACK. The key comes from the {@code TRACK17_API_KEY} environment
 * variable at runtime and is never in the repository.
 *
 * @param baseUrl {@code https://api.17track.net/track/v2.4} in production, WireMock in tests
 * @param apiKey sent as the {@code 17token} header and used to verify webhook signatures
 * @param timeout per request; a slow carrier must not hold a poller thread for long
 */
public record Track17Settings(URI baseUrl, String apiKey, Duration timeout) {

    public static final URI PRODUCTION = URI.create("https://api.17track.net/track/v2.4");

    public Track17Settings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(timeout, "timeout");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("TRACK17_API_KEY is not set");
        }
    }

    /** Never prints the key, so logging the settings (or an exception holding them) is safe. */
    @Override
    public String toString() {
        return "Track17Settings[baseUrl=" + baseUrl + ", apiKey=***, timeout=" + timeout + "]";
    }
}
