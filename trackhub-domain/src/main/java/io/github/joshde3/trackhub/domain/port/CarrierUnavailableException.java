package io.github.joshde3.trackhub.domain.port;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The carrier couldn't answer: timeout, connection error, 5xx, or rate limiting. Unlike
 * {@link TrackingNotFoundException} this is temporary, so the poller retries with backoff and the
 * API serves the last known state instead of failing.
 */
public final class CarrierUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public CarrierUnavailableException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * @param retryAfter how long the carrier asked us to wait (HTTP 429 {@code Retry-After}), or
     *     {@code null} when it didn't say
     */
    public CarrierUnavailableException(String message, Duration retryAfter, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.retryAfter = retryAfter;
    }

    /** Present when the carrier is rate limiting us and said how long to back off. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
