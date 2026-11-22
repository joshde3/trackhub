# 0005. Idempotent webhooks: record the delivery first, in the same transaction

- Status: accepted
- Date: 2026-11-22

## Context

Carriers deliver webhooks at least once. They retry on timeouts and 5xx, sometimes send the same
delivery twice, and can send copies close enough together to be processed in parallel. Applying a
delivery twice must not duplicate events, bump versions or (later) send two notifications. A
delivery we failed to apply must not be marked done.

## Decision

- `POST /webhooks/{carrier}` is outside Google sign-in. The carrier's adapter checks the signature
  on the raw bytes before anything is parsed; a bad or missing signature is 401 and stores nothing.
- One transaction per delivery:
  1. `INSERT INTO webhook_deliveries ... ON CONFLICT (carrier_code, external_event_id) DO NOTHING`.
     Zero rows inserted means we've seen it: answer 200 `duplicate` and stop.
  2. Apply the events to every user's copy of the parcel through `Shipment.applyEvents`.
  3. Commit, answer 200 `applied`.
- Any failure rolls back the delivery row with the updates, we answer 5xx, and the carrier's retry
  starts fresh. There is never a delivery marked done whose events are missing.
- Parallel copies: the second INSERT waits on the unique index until the first transaction ends,
  then does nothing. The database, not application code, serialises them.
- `ON CONFLICT DO NOTHING` rather than catching the unique violation, because in Postgres a failed
  statement aborts the whole transaction.
- 17TRACK sends no delivery id, so its adapter uses the SHA-256 of the body. A retry repeats the
  exact bytes, so it gets the same id.
- If a user's refresh saves the parcel mid-way (optimistic lock), the whole attempt rolls back and
  runs once more. That's cheap because no carrier is called inside it.
- Events have their own second line of defence: `uq_tracking_events_dedupe` (ADR 0011).

## Alternatives rejected

- **Check-then-insert (`SELECT` the delivery, then process).** Two parallel copies both see
  "not seen yet" and both apply.
- **Record the delivery after processing, or in its own transaction.** A crash between the two
  steps either loses the delivery or marks it done without its effects.
- **Redis `SETNX` for dedupe.** Works, but adds a service to the $5 plan and isn't atomic with the
  Postgres updates, which is the whole point.
- **Relying only on event dedupe.** Events would be safe, but every copy would still load and save
  shipments, and notifications (Phase 2) need delivery-level idempotency anyway.

## Consequences

- `webhook_deliveries` grows by one row per delivery. A cleanup job can delete rows older than the
  carriers' retry window (days) once volume matters.
- A webhook names a parcel, not a user, so there is one owner-less lookup
  (`findAllTracking`), backed by a new `(carrier_code, tracking_number)` index.
- Two deliveries with identical bodies are treated as one; a body that changed is a new delivery.

## Interview answer

_To be written by Joshua._

## Measured result

Ten identical deliveries sent at the same moment (`WebhookTest`): ten 200s, one applied, nine
duplicates, one delivery row, events stored once, shipment version bumped once.
