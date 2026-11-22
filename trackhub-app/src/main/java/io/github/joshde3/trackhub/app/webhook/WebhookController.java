package io.github.joshde3.trackhub.app.webhook;

import io.github.joshde3.trackhub.carriers.CarrierRegistry;
import io.github.joshde3.trackhub.domain.port.WebhookCapable;
import io.github.joshde3.trackhub.domain.port.WebhookNotification;
import io.github.joshde3.trackhub.domain.shipment.CarrierCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    WebhookController(CarrierRegistry carriers, WebhookIngestService ingest) {
        this.carriers = carriers;
        this.ingest = ingest;
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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<WebhookNotification> notification;
        try {
            notification = receiver.get().parseWebhook(raw);
        } catch (IllegalArgumentException e) {
            // Signed by the carrier but not something we understand: retrying won't help.
            LOG.warn("Unparseable {} webhook: {}", carrier, e.getMessage());
            return ResponseEntity.badRequest().body(new Result("unparseable"));
        }
        if (notification.isEmpty()) {
            return ResponseEntity.ok(new Result("ignored"));
        }
        // Exceptions from here propagate as 500 so the carrier retries; the transaction rolled back.
        return switch (ingest.ingest(code.get(), notification.get())) {
            case APPLIED -> ResponseEntity.ok(new Result("applied"));
            case DUPLICATE -> ResponseEntity.ok(new Result("duplicate"));
        };
    }

    private static Optional<CarrierCode> parse(String carrier) {
        try {
            return Optional.of(new CarrierCode(carrier));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
