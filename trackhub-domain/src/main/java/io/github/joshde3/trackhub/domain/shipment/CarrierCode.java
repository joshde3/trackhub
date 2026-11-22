package io.github.joshde3.trackhub.domain.shipment;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies a carrier adapter, e.g. {@code track17} or {@code delhivery}.
 *
 * <p>A record rather than an enum on purpose: adding a carrier must not require editing the domain
 * (ADR 0003), and an enum would have to change for every new adapter.
 */
public record CarrierCode(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z0-9-]{2,30}");

    public CarrierCode {
        Objects.requireNonNull(value, "carrier code");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Carrier code must be 2 to 30 lower-case letters, digits or hyphens, got: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
