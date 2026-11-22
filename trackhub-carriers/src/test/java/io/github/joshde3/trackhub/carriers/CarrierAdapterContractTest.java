package io.github.joshde3.trackhub.carriers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.CarrierUnavailableException;
import io.github.joshde3.trackhub.domain.port.TrackingNotFoundException;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What every carrier adapter must do, whoever the carrier is. Each adapter's test extends this and
 * only says how to make its fake server behave; the assertions are shared, so a new carrier can't
 * quietly behave differently (for example, throwing a raw {@code IOException} where the poller
 * expects {@link CarrierUnavailableException}).
 */
public abstract class CarrierAdapterContractTest {

    protected abstract CarrierAdapter adapter();

    /** Makes the fake carrier know a parcel with several scans, and returns its number. */
    protected abstract TrackingNumber givenAParcelWithScans();

    /** Makes the fake carrier reject a number outright, and returns it. */
    protected abstract TrackingNumber givenAnUnknownParcel();

    protected abstract void givenTheCarrierFails(int httpStatus);

    protected abstract void givenTheCarrierRateLimits(Duration retryAfter);

    /** Makes the carrier answer slower than the adapter's timeout. */
    protected abstract void givenTheCarrierIsSlow();

    /** Status codes this carrier really sends, each of which must map to a unified status. */
    protected abstract List<String> knownStatusCodes();

    @Test
    void tracksAParcelAsAListOfDatedEvents() {
        TrackingNumber number = givenAParcelWithScans();

        TrackingSnapshot snapshot = adapter().track(number);

        assertThat(snapshot.trackingNumber()).isEqualTo(number);
        assertThat(snapshot.events()).isNotEmpty().allSatisfy(event -> {
            assertThat(event.occurredAt()).isNotNull();
            assertThat(event.rawStatus()).isNotBlank();
        });
        assertThat(snapshot.events()).extracting(TrackingEvent::normalizedStatus).doesNotContainNull();
    }

    @Test
    void reportsAnUnknownParcelAsNotFound() {
        TrackingNumber number = givenAnUnknownParcel();

        assertThatThrownBy(() -> adapter().track(number))
                .isInstanceOf(TrackingNotFoundException.class)
                .extracting(e -> ((TrackingNotFoundException) e).trackingNumber())
                .isEqualTo(number);
    }

    @Test
    void reportsServerErrorsAsUnavailable() {
        givenTheCarrierFails(503);

        assertThatThrownBy(() -> adapter().track(TrackingNumber.of("RR123456789IN")))
                .isInstanceOf(CarrierUnavailableException.class);
    }

    @Test
    void reportsRateLimitingWithHowLongToWait() {
        givenTheCarrierRateLimits(Duration.ofSeconds(7));

        assertThatThrownBy(() -> adapter().track(TrackingNumber.of("RR123456789IN")))
                .isInstanceOfSatisfying(CarrierUnavailableException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(7)));
    }

    @Test
    void givesUpOnASlowCarrier() {
        givenTheCarrierIsSlow();

        assertThatThrownBy(() -> adapter().track(TrackingNumber.of("RR123456789IN")))
                .isInstanceOf(CarrierUnavailableException.class);
    }

    @Test
    void mapsEveryKnownStatusCode() {
        assertThat(knownStatusCodes()).isNotEmpty()
                .allSatisfy(code -> assertThat(adapter().mapStatus(code)).as(code).isPresent());
    }

    @Test
    void leavesUnknownStatusCodesUnmapped() {
        assertThat(adapter().mapStatus("SomethingNew_2031")).isEmpty();
        assertThat(adapter().mapStatus("")).isEmpty();
        assertThat(adapter().mapStatus(null)).isEmpty();
    }
}
