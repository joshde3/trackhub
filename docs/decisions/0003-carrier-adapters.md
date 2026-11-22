# 0003. Carriers behind one port, with an aggregator as the fallback

- Status: accepted
- Date: 2026-11-22

## Context

TrackHub must track parcels from many carriers. Each carrier has its own API, status vocabulary,
error behaviour and (sometimes) webhooks. Direct APIs for Indian carriers need business accounts we
may not get; 17TRACK covers 3,000+ carriers but its free plan allows only 100 new registrations a
month. Carrier code must never leak into the status rules, and a new carrier must not change them.

## Decision

- The domain defines a `CarrierAdapter` port: `code()`, `recognises(number)`, `track(number)` and
  `mapStatus(raw)`. Adapters only translate; `Shipment.applyEvents` alone decides status and
  duplicates (ADR 0004), so every carrier gets the same rules.
- Webhooks are a separate `WebhookCapable` interface (interface segregation): carriers without push
  don't implement empty methods. Signatures are checked on the raw bytes, in constant time.
- Failures are two domain exceptions: `TrackingNotFoundException` (permanent, don't retry) and
  `CarrierUnavailableException` (temporary, retry with backoff, honouring `Retry-After` on 429).
  No `IOException` or HTTP status escapes an adapter.
- Unknown carrier statuses are kept on the timeline with no normalized status, so they never move a
  shipment. A new code shows up as a warning in the logs and gets a mapping.
- `CarrierRegistry` tries direct carriers first (faster, no aggregator quota) and falls back to
  17TRACK, which accepts every number.
- The 17TRACK adapter asks for tracking info first and registers only when 17TRACK answers "not
  registered" (`-18019902`), so polling never spends registration quota.
- Mapping choices: `AvailableForPickup` becomes `OUT_FOR_DELIVERY` (the next step is the
  recipient's, which is what the user wants to be told); `Exception_Returning/Returned` become
  `RETURNED`; `NotFound` carries no news and maps to nothing.
- Adapters use the JDK `HttpClient` and Jackson, not Spring, so `trackhub-carriers` stays a plain
  library and can be tested without starting Spring.
- An abstract `CarrierAdapterContractTest` holds the behaviour every adapter must have. Each
  carrier's test extends it and only says how to make its WireMock fake behave. Tests and CI need
  no network and no API key; the key comes from `TRACK17_API_KEY` at runtime only.

## Alternatives rejected

- **Only 17TRACK.** Simplest, but one vendor's quota and outages would be ours too, and the
  interesting design (choosing between carriers) would disappear.
- **One big interface with optional webhook methods.** Carriers without webhooks would throw
  `UnsupportedOperationException`, a runtime surprise the type system can prevent.
- **Spring's `RestClient` in adapters.** Nicer API, but ties the module to Spring for little gain;
  the JDK client is enough for two POST calls.
- **Recorded responses from the live API in CI.** Real data, but needs the key in CI and makes tests
  flaky. Fixtures are written from the docs for now and get replaced by scrubbed recordings once we
  make a real call.

## Consequences

- Adding Delhivery or India Post means one adapter class plus one test extending the contract test.
- The first `track()` of a new number returns no events: 17TRACK fetches them in the background and
  they arrive by webhook or on the next poll. The UI must show "waiting for the carrier".
- 17TRACK's limit of 3 requests per second is enforced by the caller (the poller, in Phase 2),
  not the adapter; the adapter only reports 429 with how long to wait.
- Fixtures could drift from the real API until replaced with recordings.

## Interview answer

_To be written by Joshua._

## Measured result

_After Phase 1: carriers coverage, and how many polls spent zero registration quota._
