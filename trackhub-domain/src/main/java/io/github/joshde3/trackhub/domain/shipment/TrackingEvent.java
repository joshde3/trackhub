package io.github.joshde3.trackhub.domain.shipment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * One scan reported by a carrier, as it appears on the parcel's timeline.
 *
 * @param occurredAt when the carrier says it happened (not when we heard about it)
 * @param rawStatus the carrier's own status text or code, kept for audit and re-mapping
 * @param normalizedStatus our unified status, or {@code null} when the adapter could not map it
 * @param location where it happened, if the carrier says
 * @param description human-readable text from the carrier, if any
 */
public record TrackingEvent(
        Instant occurredAt,
        String rawStatus,
        ShipmentStatus normalizedStatus,
        String location,
        String description) {

    public TrackingEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(rawStatus, "rawStatus");
    }

    /** The unified status, or empty when the carrier's status is unknown to us. */
    public Optional<ShipmentStatus> status() {
        return Optional.ofNullable(normalizedStatus);
    }

    /**
     * Identity of the scan itself. The same scan reported twice (by a webhook and by a poll, or by
     * a carrier retrying) has the same hash, so it lands on the timeline once.
     *
     * <p>The carrier is not part of the hash because every event belongs to exactly one shipment,
     * which has exactly one carrier. Description is excluded because carriers sometimes re-word
     * the same scan.
     */
    public String dedupeHash() {
        String key = occurredAt + "|" + rawStatus + "|" + (location == null ? "" : location);
        return sha256(key);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
