package io.github.joshde3.trackhub.domain.port;

import java.util.Map;
import java.util.Optional;

/**
 * Extra capability for carriers that push updates. Kept separate from {@link CarrierAdapter}
 * (interface segregation): carriers without webhooks don't implement empty methods, and the
 * webhook endpoint can only be wired to adapters that really support it.
 */
public interface WebhookCapable {

    /**
     * Checks that the request really came from the carrier. Must run on the exact bytes received,
     * before any parsing: re-serialised JSON would not match the signature.
     *
     * @param headers request headers; adapters look names up case-insensitively
     */
    boolean verifySignature(Map<String, String> headers, byte[] rawBody);

    /**
     * Parses a verified webhook body. Empty for event types we don't act on.
     *
     * @throws IllegalArgumentException if the body isn't a webhook this carrier sends
     */
    Optional<WebhookNotification> parseWebhook(byte[] rawBody);
}
