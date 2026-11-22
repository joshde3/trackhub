# 0004. Unified status state machine with a "late news never wins" rule

- Status: accepted
- Date: 2026-11-22

## Context

Every carrier has its own status vocabulary, and carriers deliver events late, out of order and
more than once (a webhook and a poll can report the same scan). The app needs one status per
parcel that only ever moves in a sensible direction, and one notification per real change.

## Decision

- `ShipmentStatus` is an enum of six states. `canMoveTo` encodes the allowed moves: forward along
  CREATED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED (jumps allowed, since carriers skip steps); any
  non-terminal state to EXCEPTION or RETURNED; EXCEPTION back onto the happy path. DELIVERED and
  RETURNED are terminal.
- `Shipment.applyEvents` is the only way status changes. It drops events it has already seen
  (by a hash of time, raw status and location), processes new ones oldest first, and lets an event
  move the status only if it is allowed and not older than the event that set the current status.
- Every event joins the timeline, including ones that move nothing and ones whose carrier status
  could not be mapped.
- `applyEvents` returns the net change of a batch, which is what notifications are sent for.

## Alternatives rejected

- **Last event wins.** Simple, but a late or replayed event can move a delivered parcel back to
  "in transit" and trigger a wrong notification.
- **Highest status wins (a fixed rank).** Cannot express recovery from EXCEPTION.
- **RETURNED only from EXCEPTION** (as first drawn in the proposal). Rejected while coding: some
  carriers report return-to-origin directly, and refusing it would leave the parcel stuck.

## Consequences

- A carrier that reports OUT_FOR_DELIVERY and later IN_TRANSIT (a rescheduled delivery) leaves the
  parcel at OUT_FOR_DELIVERY until a newer event arrives. The adapter should map "delivery
  rescheduled" scans to EXCEPTION. Watch the rejected-transition count per carrier.

## Interview answer

_To be written by Joshua._

## Measured result

_Phase 2 end: rejected or reordered transitions per carrier._
