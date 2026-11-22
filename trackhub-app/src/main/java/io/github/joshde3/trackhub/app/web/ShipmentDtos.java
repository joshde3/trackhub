package io.github.joshde3.trackhub.app.web;

import io.github.joshde3.trackhub.app.shipment.ShipmentQueries;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.github.joshde3.trackhub.domain.shipment.ShipmentStatus;
import io.github.joshde3.trackhub.domain.shipment.TrackingEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The API's JSON shapes. Kept separate from domain objects so that renaming a domain field can't
 * silently break the Android app, and so the domain never needs JSON annotations (ADR 0002).
 */
final class ShipmentDtos {

    private ShipmentDtos() {}

    record AddShipmentRequest(
            @Schema(example = "RR123456789IN", description = "Spaces and hyphens are ignored")
            @NotBlank @Size(max = 60) String trackingNumber,
            @Schema(example = "track17", description = "Leave out to detect the carrier from the number")
            @Pattern(regexp = "[a-z0-9-]{2,30}") String carrier) {}

    record EventResponse(Instant occurredAt, ShipmentStatus status, String rawStatus, String location, String description) {
        static EventResponse of(TrackingEvent event) {
            return new EventResponse(event.occurredAt(), event.normalizedStatus(), event.rawStatus(), event.location(), event.description());
        }
    }

    record ShipmentResponse(
            UUID id,
            String carrier,
            String trackingNumber,
            ShipmentStatus status,
            Instant statusChangedAt,
            Instant createdAt,
            @Schema(description = "Oldest first") List<EventResponse> events) {
        static ShipmentResponse of(Shipment shipment) {
            return new ShipmentResponse(
                    shipment.id(),
                    shipment.carrier().value(),
                    shipment.trackingNumber().value(),
                    shipment.status(),
                    shipment.statusChangedAt(),
                    shipment.createdAt(),
                    shipment.events().stream().map(EventResponse::of).toList());
        }
    }

    record ShipmentSummaryResponse(
            UUID id, String carrier, String trackingNumber, ShipmentStatus status, Instant statusChangedAt, Instant createdAt) {
        static ShipmentSummaryResponse of(ShipmentQueries.Summary summary) {
            return new ShipmentSummaryResponse(summary.id(), summary.carrier(), summary.trackingNumber(),
                    summary.status(), summary.statusChangedAt(), summary.createdAt());
        }
    }

    record PageResponse<T>(List<T> items, int page, int size, boolean hasMore) {}
}
