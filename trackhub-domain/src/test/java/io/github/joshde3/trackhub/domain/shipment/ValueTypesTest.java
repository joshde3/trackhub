package io.github.joshde3.trackhub.domain.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValueTypesTest {

    @Test
    void trackingNumberIsNormalized() {
        assertThat(TrackingNumber.of(" rr 1234-5678 9in ")).isEqualTo(new TrackingNumber("RR123456789IN"));
        assertThat(TrackingNumber.of("rr123456789in")).hasToString("RR123456789IN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1234", "RR12345678!IN", "A123456789012345678901234567890123456789X"})
    void trackingNumberRejectsInvalidInput(String raw) {
        assertThatThrownBy(() -> TrackingNumber.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trackingNumberRejectsNull() {
        assertThatThrownBy(() -> TrackingNumber.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void carrierCodeAcceptsAdapterNames() {
        assertThat(new CarrierCode("india-post")).hasToString("india-post");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "Delhivery", "india post"})
    void carrierCodeRejectsInvalidNames(String raw) {
        assertThatThrownBy(() -> new CarrierCode(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusChangeNeedsTwoDifferentStatuses() {
        assertThatThrownBy(() -> new StatusChange(
                ShipmentStatus.IN_TRANSIT, ShipmentStatus.IN_TRANSIT, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventWithoutLocationStillHasAStableHash() {
        var a = new TrackingEvent(Instant.EPOCH, "X", null, null, null);
        var b = new TrackingEvent(Instant.EPOCH, "X", null, null, "other text");

        assertThat(a.dedupeHash()).isEqualTo(b.dedupeHash()).hasSize(64);
        assertThat(a.status()).isEmpty();
    }
}
