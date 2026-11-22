package io.github.joshde3.trackhub.app.web;

import io.github.joshde3.trackhub.app.shipment.ShipmentNotFoundException;
import io.github.joshde3.trackhub.app.shipment.UnknownCarrierException;
import io.github.joshde3.trackhub.domain.port.CarrierUnavailableException;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository.ConcurrentUpdateException;
import io.github.joshde3.trackhub.domain.port.ShipmentRepository.DuplicateShipmentException;
import io.github.joshde3.trackhub.domain.port.TrackingNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 9457 problem document with a stable {@code code}, so the Android app
 * switches on {@code code} rather than parsing messages. Spring's own errors (bad JSON, failed
 * validation, 404 routes) come out in the same shape via {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ShipmentNotFoundException.class)
    ProblemDetail notFound(ShipmentNotFoundException e) {
        // 404, not 403, for someone else's parcel: 403 would confirm the id exists.
        return problem(HttpStatus.NOT_FOUND, "shipment-not-found", "No such shipment");
    }

    @ExceptionHandler(DuplicateShipmentException.class)
    ProblemDetail duplicate(DuplicateShipmentException e) {
        return problem(HttpStatus.CONFLICT, "already-tracking", e.getMessage());
    }

    @ExceptionHandler(TrackingNotFoundException.class)
    ProblemDetail unknownParcel(TrackingNotFoundException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "unknown-tracking-number",
                "The carrier has no parcel " + e.trackingNumber() + ". Check the number and carrier.");
    }

    @ExceptionHandler(UnknownCarrierException.class)
    ProblemDetail unknownCarrier(UnknownCarrierException e) {
        return problem(HttpStatus.BAD_REQUEST, "unknown-carrier", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidInput(IllegalArgumentException e) {
        // Value types such as TrackingNumber reject bad input with a readable message.
        return problem(HttpStatus.BAD_REQUEST, "invalid-input", e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidParameter(ConstraintViolationException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-input", e.getMessage());
    }

    @ExceptionHandler(CarrierUnavailableException.class)
    ResponseEntity<ProblemDetail> carrierDown(CarrierUnavailableException e) {
        LOG.warn("Carrier unavailable: {}", e.getMessage());
        long seconds = e.retryAfter().map(d -> Math.max(1, d.toSeconds())).orElse(30L);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                // The carrier's message can contain internal detail; the client gets a generic one.
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "carrier-unavailable",
                        "The carrier isn't answering right now. Your parcel's last known status is unchanged."));
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    ProblemDetail conflict(ConcurrentUpdateException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-update", "The shipment changed while we were updating it. Try again.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
