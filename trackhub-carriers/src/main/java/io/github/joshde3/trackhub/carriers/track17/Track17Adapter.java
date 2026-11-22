package io.github.joshde3.trackhub.carriers.track17;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.joshde3.trackhub.domain.port.CarrierAdapter;
import io.github.joshde3.trackhub.domain.port.CarrierUnavailableException;
import io.github.joshde3.trackhub.domain.port.TrackingNotFoundException;
import io.github.joshde3.trackhub.domain.port.TrackingSnapshot;
import io.github.joshde3.trackhub.domain.port.WebhookCapable;
import io.github.joshde3.trackhub.domain.port.WebhookNotification;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.github.joshde3.trackhub.domain.shipment.TrackingNumber;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter for the 17TRACK aggregator, API v2.4 (https://api.17track.net/en/doc).
 *
 * <p>17TRACK only tracks numbers that were registered first, and registration is what costs quota
 * (100 a month on the free plan). So {@link #track} asks for tracking info first and registers
 * only when 17TRACK says the number is unknown. 17TRACK then fetches from the real carrier in the
 * background, so a freshly registered parcel has no events yet; they arrive by webhook or on the
 * next poll.
 */
public final class Track17Adapter implements CarrierAdapter, WebhookCapable {

    public static final CarrierCode CODE = new CarrierCode("track17");

    /** {@code rejected[].error.code} for "number not registered yet". */
    static final int NOT_REGISTERED = -18019902;

    private static final System.Logger LOG = System.getLogger(Track17Adapter.class.getName());
    private static final String UNKNOWN_STATUS = "Unknown";

    private final Track17Settings settings;
    private final HttpClient http;
    private final ObjectMapper json;

    public Track17Adapter(Track17Settings settings, HttpClient http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    public Track17Adapter(Track17Settings settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(settings.timeout()).build(), new ObjectMapper());
    }

    @Override
    public CarrierCode code() {
        return CODE;
    }

    /** An aggregator: it detects the real carrier itself, so every number is worth a try. */
    @Override
    public boolean recognises(TrackingNumber number) {
        return true;
    }

    @Override
    public TrackingSnapshot track(TrackingNumber number) {
        JsonNode info = post("/gettrackinfo", number);
        Optional<JsonNode> accepted = first(info, "accepted");
        if (accepted.isPresent()) {
            return toSnapshot(number, accepted.get());
        }
        JsonNode rejected = first(info, "rejected").orElseThrow(() -> unexpected("gettrackinfo", info));
        if (rejected.path("error").path("code").asInt() != NOT_REGISTERED) {
            throw notFound(number, rejected);
        }

        JsonNode registration = post("/register", number);
        if (first(registration, "accepted").isEmpty()) {
            throw notFound(number, first(registration, "rejected").orElseThrow(() -> unexpected("register", registration)));
        }
        LOG.log(System.Logger.Level.INFO, "Registered {0} with 17TRACK", number);
        return new TrackingSnapshot(number, null, List.of());
    }

    /**
     * 17TRACK reports {@code sub_status} values such as {@code InTransit_Arrival}, or a bare main
     * status ({@code Delivered}) or {@code stage} ({@code PickedUp}). The part before the underscore
     * decides, except for returns, which 17TRACK files under Exception.
     */
    @Override
    public Optional<ShipmentStatus> mapStatus(String rawStatus) {
        if (rawStatus == null) {
            return Optional.empty();
        }
        if (rawStatus.equals("Exception_Returning") || rawStatus.equals("Exception_Returned")) {
            return Optional.of(ShipmentStatus.RETURNED);
        }
        int underscore = rawStatus.indexOf('_');
        String main = underscore < 0 ? rawStatus : rawStatus.substring(0, underscore);
        return Optional.ofNullable(switch (main) {
            case "InfoReceived" -> ShipmentStatus.CREATED;
            case "InTransit", "PickedUp", "Departure", "Arrival" -> ShipmentStatus.IN_TRANSIT;
            // Waiting at a pickup point: like out for delivery, the next step is the recipient's.
            case "OutForDelivery", "AvailableForPickup" -> ShipmentStatus.OUT_FOR_DELIVERY;
            case "Delivered" -> ShipmentStatus.DELIVERED;
            case "DeliveryFailure", "Exception", "Expired" -> ShipmentStatus.EXCEPTION;
            case "Returning", "Returned" -> ShipmentStatus.RETURNED;
            // NotFound carries no news, and unknown codes must never move a shipment.
            default -> null;
        });
    }

    /** 17TRACK signs with {@code sign = sha256hex(rawBody + "/" + apiKey)}. */
    @Override
    public boolean verifySignature(Map<String, String> headers, byte[] rawBody) {
        String received = headers.entrySet().stream()
                .filter(header -> header.getKey().equalsIgnoreCase("sign"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (received == null) {
            return false;
        }
        byte[] suffix = ("/" + settings.apiKey()).getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[rawBody.length + suffix.length];
        System.arraycopy(rawBody, 0, signed, 0, rawBody.length);
        System.arraycopy(suffix, 0, signed, rawBody.length, suffix.length);
        byte[] expected = Hashing.sha256Hex(signed).getBytes(StandardCharsets.US_ASCII);
        // Constant-time comparison, so response timing doesn't reveal how much of a forged
        // signature was right.
        return MessageDigest.isEqual(expected, received.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public Optional<WebhookNotification> parseWebhook(byte[] rawBody) {
        JsonNode body;
        try {
            body = json.readTree(rawBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("Webhook body is not JSON", e);
        }
        JsonNode data = body.path("data");
        String rawNumber = data.path("number").asText("");
        if (rawNumber.isEmpty()) {
            throw new IllegalArgumentException("Webhook has no data.number");
        }
        TrackingNumber number = TrackingNumber.of(rawNumber);
        // 17TRACK sends no delivery id. A retry repeats the exact bytes, so the body hash is one.
        String deliveryId = Hashing.sha256Hex(rawBody);
        return switch (body.path("event").asText("")) {
            case "TRACKING_UPDATED" -> Optional.of(new WebhookNotification.Updated(deliveryId, toSnapshot(number, data)));
            case "TRACKING_STOPPED" -> Optional.of(new WebhookNotification.Stopped(deliveryId, number));
            default -> Optional.empty();
        };
    }

    private JsonNode post(String path, TrackingNumber number) {
        ArrayNode payload = json.createArrayNode();
        payload.addObject().put("number", number.value());
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.baseUrl() + path))
                .timeout(settings.timeout())
                .header("17token", settings.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new CarrierUnavailableException("17TRACK " + path + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CarrierUnavailableException("Interrupted calling 17TRACK " + path, e);
        }

        int status = response.statusCode();
        if (status == 429) {
            throw new CarrierUnavailableException("17TRACK rate limit hit on " + path, retryAfter(response), null);
        }
        if (status == 401 || status == 403) {
            // Never include the key: this message ends up in logs.
            throw new CarrierUnavailableException("17TRACK rejected the API key (HTTP " + status + ")", null);
        }
        if (status < 200 || status >= 300) {
            throw new CarrierUnavailableException("17TRACK " + path + " returned HTTP " + status, null);
        }

        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (IOException e) {
            throw new CarrierUnavailableException("17TRACK " + path + " returned invalid JSON", e);
        }
        if (body.path("code").asInt(-1) != 0) {
            throw new CarrierUnavailableException("17TRACK " + path + " returned code " + body.path("code"), null);
        }
        return body;
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
            } catch (NumberFormatException e) {
                return Optional.empty(); // an HTTP date; rare enough to fall back to our own backoff
            }
        }).orElse(null);
    }

    private static Optional<JsonNode> first(JsonNode body, String list) {
        JsonNode items = body.path("data").path(list);
        return items.isArray() && !items.isEmpty() ? Optional.of(items.get(0)) : Optional.empty();
    }

    private TrackingSnapshot toSnapshot(TrackingNumber number, JsonNode item) {
        String upstream = null;
        List<TrackingEvent> events = new ArrayList<>();
        for (JsonNode provider : item.path("track_info").path("tracking").path("providers")) {
            if (upstream == null) {
                upstream = textOrNull(provider.path("provider").path("name"));
            }
            for (JsonNode event : provider.path("events")) {
                toEvent(number, event).ifPresent(events::add);
            }
        }
        return new TrackingSnapshot(number, upstream, events);
    }

    private Optional<TrackingEvent> toEvent(TrackingNumber number, JsonNode event) {
        Optional<Instant> occurredAt = parseTime(event.path("time_utc")).or(() -> parseTime(event.path("time_iso")));
        if (occurredAt.isEmpty()) {
            // Without a time we can't order the event or dedupe it reliably, so we drop it.
            LOG.log(System.Logger.Level.WARNING, "Dropping 17TRACK event without a time for {0}", number);
            return Optional.empty();
        }
        String raw = Optional.ofNullable(textOrNull(event.path("sub_status")))
                .or(() -> Optional.ofNullable(textOrNull(event.path("stage"))))
                .orElse(UNKNOWN_STATUS);
        ShipmentStatus status = mapStatus(raw).orElse(null);
        if (status == null && !raw.startsWith("NotFound")) {
            LOG.log(System.Logger.Level.WARNING, "Unmapped 17TRACK status {0}", raw);
        }
        return Optional.of(new TrackingEvent(
                occurredAt.get(), raw, status, textOrNull(event.path("location")), textOrNull(event.path("description"))));
    }

    private static Optional<Instant> parseTime(JsonNode node) {
        String text = textOrNull(node);
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static TrackingNotFoundException notFound(TrackingNumber number, JsonNode rejected) {
        JsonNode error = rejected.path("error");
        return new TrackingNotFoundException(number, error.path("message").asText("rejected") + " (" + error.path("code").asText() + ")");
    }

    private static CarrierUnavailableException unexpected(String path, JsonNode body) {
        return new CarrierUnavailableException("17TRACK " + path + " returned neither accepted nor rejected items", null);
    }
}
