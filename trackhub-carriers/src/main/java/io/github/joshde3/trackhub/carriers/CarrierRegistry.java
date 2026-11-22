package io.github.joshde3.trackhub.carriers;

import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.WebhookCapable;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Picks the adapter for a parcel. Direct carrier integrations (Delhivery, India Post) are tried
 * first because they are faster and don't spend aggregator quota; the aggregator (17TRACK) is the
 * fallback that can track almost anything (ADR 0003).
 *
 * <p>Adding a carrier means adding one adapter to the list. Nothing here or in the domain changes.
 */
public final class CarrierRegistry {

    private final List<CarrierAdapter> direct;
    private final CarrierAdapter fallback;
    private final Map<CarrierCode, CarrierAdapter> byCode = new LinkedHashMap<>();

    public CarrierRegistry(List<? extends CarrierAdapter> direct, CarrierAdapter fallback) {
        this.direct = List.copyOf(direct);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        List<CarrierAdapter> all = new ArrayList<>(this.direct);
        all.add(fallback);
        for (CarrierAdapter adapter : all) {
            if (byCode.putIfAbsent(adapter.code(), adapter) != null) {
                throw new IllegalArgumentException("Two adapters share the code " + adapter.code());
            }
        }
    }

    /** The adapter a stored shipment was created with. */
    public Optional<CarrierAdapter> byCode(CarrierCode code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** The best adapter for a number the user didn't label with a carrier. Never empty. */
    public CarrierAdapter detect(TrackingNumber number) {
        return direct.stream().filter(adapter -> adapter.recognises(number)).findFirst().orElse(fallback);
    }

    /** The adapter behind {@code /webhooks/{carrier}}, if that carrier pushes updates at all. */
    public Optional<WebhookCapable> webhookReceiver(CarrierCode code) {
        return byCode(code).filter(WebhookCapable.class::isInstance).map(WebhookCapable.class::cast);
    }

    public List<CarrierCode> codes() {
        return List.copyOf(byCode.keySet());
    }
}
