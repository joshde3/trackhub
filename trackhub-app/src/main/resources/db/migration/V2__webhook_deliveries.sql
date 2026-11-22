-- V2: carrier webhooks (ADR 0005).

-- Idempotency: one row per carrier delivery. The webhook handler inserts here FIRST, in the same
-- transaction as the shipment updates. A retried or duplicated delivery hits the unique
-- constraint and is answered 200 without doing anything; a failed one rolls back this row too, so
-- the carrier's retry runs fresh.
CREATE TABLE webhook_deliveries (
    id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    carrier_code       text        NOT NULL,
    -- The carrier's delivery id, or a SHA-256 of the body when it sends none (17TRACK).
    external_event_id  text        NOT NULL,
    tracking_number    text        NOT NULL,
    kind               text        NOT NULL CHECK (kind IN ('UPDATED', 'STOPPED')),
    received_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_webhook_deliveries_event UNIQUE (carrier_code, external_event_id)
);

-- A webhook names a parcel, not a user: find every user's copy of it. The owner-first unique
-- index can't serve this lookup because user_id is its leading column.
CREATE INDEX ix_shipments_carrier_tracking ON shipments (carrier_code, tracking_number);
