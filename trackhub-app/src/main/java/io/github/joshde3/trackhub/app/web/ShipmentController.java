package io.github.joshde3.trackhub.app.web;

import io.github.joshde3.trackhub.app.security.UserAccounts;
import io.github.joshde3.trackhub.app.shipment.ShipmentQueries;
import io.github.joshde3.trackhub.app.shipment.ShipmentService;
import io.github.joshde3.trackhub.app.web.ShipmentDtos.AddShipmentRequest;
import io.github.joshde3.trackhub.app.web.ShipmentDtos.PageResponse;
import io.github.joshde3.trackhub.app.web.ShipmentDtos.ShipmentResponse;
import io.github.joshde3.trackhub.app.web.ShipmentDtos.ShipmentSummaryResponse;
import io.github.joshde3.trackhub.domain.shipment.Shipment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin: parse, call one use case, map the result. Rules live in the service and the domain. */
@RestController
@RequestMapping("/api/shipments")
@Validated
@Tag(name = "Shipments")
@SecurityRequirement(name = OpenApiConfiguration.GOOGLE_ID_TOKEN)
class ShipmentController {

    private final ShipmentService service;
    private final ShipmentQueries queries;
    private final UserAccounts users;

    ShipmentController(ShipmentService service, ShipmentQueries queries, UserAccounts users) {
        this.service = service;
        this.queries = queries;
        this.users = users;
    }

    @PostMapping
    @Operation(summary = "Start tracking a parcel")
    @ApiResponse(responseCode = "201", description = "Tracking; events may be empty if the carrier is busy or slow")
    @ApiResponse(responseCode = "409", description = "You already track this parcel")
    @ApiResponse(responseCode = "422", description = "The carrier has no such parcel")
    ResponseEntity<ShipmentResponse> add(@AuthenticationPrincipal Jwt token, @Valid @RequestBody AddShipmentRequest request) {
        Shipment shipment = service.add(users.idOf(token), request.trackingNumber(), request.carrier());
        return ResponseEntity.created(URI.create("/api/shipments/" + shipment.id())).body(ShipmentResponse.of(shipment));
    }

    @GetMapping
    @Operation(summary = "Your parcels, newest first")
    PageResponse<ShipmentSummaryResponse> list(
            @AuthenticationPrincipal Jwt token,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        ShipmentQueries.Page result = queries.newestFirst(users.idOf(token), page, size);
        return new PageResponse<>(result.items().stream().map(ShipmentSummaryResponse::of).toList(), page, size, result.hasMore());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One parcel with its timeline")
    @ApiResponse(responseCode = "404", description = "No such parcel, or it isn't yours")
    ShipmentResponse get(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return ShipmentResponse.of(service.get(users.idOf(token), id));
    }

    @PostMapping("/{id}/refresh")
    @Operation(summary = "Ask the carrier for news now")
    @ApiResponse(responseCode = "503", description = "The carrier is unavailable; see Retry-After")
    ShipmentResponse refresh(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return ShipmentResponse.of(service.refresh(users.idOf(token), id));
    }
}
