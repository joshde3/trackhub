-- V1: users, shipments and their timelines (ADR 0011).
-- Later tables (jobs, webhook_deliveries, notifications, ...) arrive in their own migrations,
-- in the pull request that first uses them.

-- Trigram index support for "search by tracking number or label".
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id          uuid        PRIMARY KEY,
    google_sub  text        NOT NULL UNIQUE,
    email       text        NOT NULL UNIQUE,
    name        text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shipments (
    id                 uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    carrier_code       text        NOT NULL,
    tracking_number    text        NOT NULL,
    upstream_carrier   text,
    label              text,
    status             text        NOT NULL
        CHECK (status IN ('CREATED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'EXCEPTION', 'RETURNED')),
    status_changed_at  timestamptz NOT NULL,
    status_event_at    timestamptz,
    created_at         timestamptz NOT NULL,
    archived_at        timestamptz,
    version            bigint      NOT NULL,
    -- One user tracks a parcel once. Two users may track the same parcel.
    CONSTRAINT uq_shipments_owner_tracking UNIQUE (user_id, carrier_code, tracking_number)
);

-- "My parcels, newest first" on the dashboard.
CREATE INDEX ix_shipments_user_created ON shipments (user_id, created_at DESC);
-- Search box: substring match on tracking number and label.
CREATE INDEX ix_shipments_tracking_trgm ON shipments USING gin (tracking_number gin_trgm_ops);
CREATE INDEX ix_shipments_label_trgm ON shipments USING gin (label gin_trgm_ops);

CREATE TABLE tracking_events (
    id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id        uuid        NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    occurred_at        timestamptz NOT NULL,
    raw_status         text        NOT NULL,
    normalized_status  text,
    location           text,
    description        text,
    dedupe_hash        text        NOT NULL,  -- SHA-256 hex, 64 chars
    -- Second line of defence after Shipment.applyEvents: the same scan can never be stored twice.
    CONSTRAINT uq_tracking_events_dedupe UNIQUE (shipment_id, dedupe_hash)
);

CREATE INDEX ix_tracking_events_timeline ON tracking_events (shipment_id, occurred_at);
