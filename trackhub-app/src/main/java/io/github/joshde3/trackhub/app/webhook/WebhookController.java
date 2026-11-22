package io.github.joshde3.trackhub.app.webhook;

import io.github.joshde3.trackhub.carriers.CarrierRegistry;
import io.github.joshde3.trackhub.domain.port.WebhookCapable;
import io.github.joshde3.trackhub.domain.port.WebhookNotification;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives carrier pushes at {@code /webhooks/{carrier}}. Not behind Google sign-in: carriers
 * authenticate with a signature over the raw body instead, so the body is taken as bytes and
 * checked before it is parsed.
 */
@RestController
@Tag(name = "Webhooks")
class WebhookController {

    record Result(String result) {}

    private static final Logger LOG = LoggerFactory.getLogger(WebhookController.class);

    private final CarrierRegistry carriers;
    private final WebhookIngestService ingest;
    private final MeterRegistry metrics;

    WebhookController(CarrierRegistry carriers, WebhookIngestService ingest, MeterRegistry metrics) {
        this.carriers = carriers;
        this.ingest = ingest;
        this.metrics = metrics;
    }

    @PostMapping("/webhooks/{carrier}")
    @Operation(summary = "Carrier push endpoint (signed by the carrier, not for apps)")
    ResponseEntity<Result> receive(
            @PathVariable String carrier,
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        Optional<CarrierCode> code = parse(carrier);
        Optional<WebhookCapable> receiver = code.flatMap(carriers::webhookReceiver);
        if (receiver.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        byte[] raw = body == null ? new byte[0] : body;
        if (!receiver.get().verifySignature(headers, raw)) {
            LOG.warn("Rejected {} webhook with a bad signature", carrier);
            return count(code.get(), "rejected", ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        Optional<WebhookNotification> notification;
        try {
            notification = receiver.get().parseWebhook(raw);
        } catch (IllegalArgumentException e) {
            // Signed by the carrier but not something we understand: retrying won't help.
            LOG.warn("Unparseable {} webhook: {}", carrier, e.getMessage());
            return count(code.get(), "unparseable", ResponseEntity.badRequest().body(new Result("unparseable")));
        }
        if (notification.isEmpty()) {
            return count(code.get(), "ignored", ResponseEntity.ok(new Result("ignored")));
        }
        MDC.put("deliveryId", notification.get().deliveryId());
        try {
            // Exceptions from here propagate as 500 so the carrier retries; the transaction rolled back.
            String result = switch (ingest.ingest(code.get(), notification.get())) {
                case APPLIED -> "applied";
                case DUPLICATE -> "duplicate";
            };
            return count(code.get(), result, ResponseEntity.ok(new Result(result)));
        } finally {
            MDC.remove("deliveryId");
        }
    }

    /** {@code trackhub.webhooks{carrier,result}}: duplicates absorbed is one of the Phase 1 numbers. */
    private <T> ResponseEntity<T> count(CarrierCode carrier, String result, ResponseEntity<T> response) {
        metrics.counter("trackhub.webhooks", "carrier", carrier.value(), "result", result).increment();
        return response;
    }

    private static Optional<CarrierCode> parse(String carrier) {
        try {
            return Optional.of(new CarrierCode(carrier));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
