package io.github.joshde3.trackhub.app.shipment;

/** The client named a carrier we have no adapter for. */
public final class UnknownCarrierException extends RuntimeException {
    public UnknownCarrierException(String code) {
        super("Unknown carrier: " + code);
    }
}
