package io.github.joshde3.trackhub.domain.shipment;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A carrier tracking number, normalized so that "rr 1234-5678 in" and "RR12345678IN" are the same
 * parcel: spaces and hyphens removed, upper case.
 */
public record TrackingNumber(String value) {

    private static final Pattern VALID = Pattern.compile("[A-Z0-9]{5,40}");

    public TrackingNumber {
        Objects.requireNonNull(value, "tracking number");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Tracking number must be 5 to 40 letters or digits, got: " + value);
        }
    }

    /** Normalizes user or carrier input, then validates it. */
    public static TrackingNumber of(String raw) {
        Objects.requireNonNull(raw, "tracking number");
        return new TrackingNumber(raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
