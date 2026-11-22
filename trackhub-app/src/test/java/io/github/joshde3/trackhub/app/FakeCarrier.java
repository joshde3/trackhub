package io.github.joshde3.trackhub.app;

import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.CarrierUnavailableException;
import io.github.joshde3.trackhub.domain.port.TrackingNotFoundException;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** An in-memory carrier for API tests: each test says what the carrier knows about a number. */
public final class FakeCarrier implements CarrierAdapter {

    public static final CarrierCode CODE = new CarrierCode("fake");

    private sealed interface Answer {}
    private record Events(List<TrackingEvent> events) implements Answer {}
    private record Unknown() implements Answer {}
    private record Down() implements Answer {}

    private final Map<TrackingNumber, Answer> answers = new ConcurrentHashMap<>();
    private final Map<TrackingNumber, Runnable> whileTracking = new ConcurrentHashMap<>();

    /** Runs {@code action} during the next call for this number, e.g. a webhook saving meanwhile. */
    public void duringNextCall(TrackingNumber number, Runnable action) {
        whileTracking.put(number, action);
    }

    public void knows(TrackingNumber number, TrackingEvent... events) {
        answers.put(number, new Events(List.of(events)));
    }

    public void doesNotKnow(TrackingNumber number) {
        answers.put(number, new Unknown());
    }

    public void isDownFor(TrackingNumber number) {
        answers.put(number, new Down());
    }

    @Override
    public CarrierCode code() {
        return CODE;
    }

    @Override
    public boolean recognises(TrackingNumber number) {
        return true;
    }

    @Override
    public TrackingSnapshot track(TrackingNumber number) {
        Runnable action = whileTracking.remove(number);
        if (action != null) {
            action.run();
        }
        return switch (answers.getOrDefault(number, new Events(List.of()))) {
            case Events(List<TrackingEvent> events) -> new TrackingSnapshot(number, null, events);
            case Unknown() -> throw new TrackingNotFoundException(number, "no such parcel");
            case Down() -> throw new CarrierUnavailableException("fake carrier down", Duration.ofSeconds(12), null);
        };
    }

    @Override
    public Optional<ShipmentStatus> mapStatus(String rawStatus) {
        return Optional.empty();
    }
}
