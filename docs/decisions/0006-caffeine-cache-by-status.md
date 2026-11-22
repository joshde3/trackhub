# 0006. Cache carrier answers in-process, with a TTL set by the parcel's status

- Status: accepted
- Date: 2026-11-22

## Context

Every carrier call costs time (hundreds of milliseconds to seconds), counts against a rate limit
(17TRACK: 3 requests a second) and sometimes quota. Users tap "refresh" repeatedly, and the
poller (Phase 2) will ask about the same parcels over and over. How fast a parcel's information
changes depends on where it is: out for delivery, within the hour; delivered, never again.

## Decision

- Carrier answers (`TrackingSnapshot`) are cached per `carrier:trackingNumber` in Caffeine, behind
  Spring's cache abstraction (`@Cacheable` on `CarrierLookup.track`).
- Each entry's TTL comes from the newest status it reports: out for delivery 5 min, in transit or
  exception 30 min, created 2 h, delivered or returned 24 h, no scans yet 2 min. At most 10,000
  entries, so memory stays bounded (a few MB).
- Only successes are cached. "Not found" and outages are not, so a recovered carrier is seen on
  the next try.
- A webhook's snapshot replaces the cached one (`@CachePut`) after its transaction commits, so the
  cache never shows something the database rolled back.
- `CarrierLookup` is the only path to a carrier, so it also records `trackhub.carrier.calls`
  (a timer tagged by carrier and outcome). It runs only on cache misses: it counts real calls.

### Observability in the same PR

- Logs are JSON lines (Elastic Common Schema, Spring Boot's built-in structured logging). Every
  request gets an `X-Request-Id`, which goes into the MDC and so into every log line of that
  request. Webhook logs also carry `deliveryId`.
- Metrics are pushed over OTLP to Grafana Cloud's free tier when `OTLP_METRICS_ENABLED` is set,
  with histograms for `http.server.requests` and `trackhub.carrier.calls` (p50/p95/p99),
  `cache.gets` hits and misses, and `trackhub.webhooks{result}` (applied, duplicate, rejected...).

## Alternatives rejected

- **Redis.** Shared across instances and survives restarts, but there is one instance, a restart
  only costs some carrier calls, and a Redis service would cost more than the app on Railway.
  Because the code uses Spring's cache abstraction, switching is a dependency and configuration
  change (optional Phase 5).
- **One TTL for everything.** Short enough for parcels out for delivery means re-asking about
  delivered parcels all day; long enough for those means stale "out for delivery".
- **Caching inside the carrier adapters.** Each adapter would reimplement it, and the carriers
  module would need to know about TTL policy and metrics.
- **Prometheus scraping.** Needs an exposed metrics endpoint and a scraper that can reach the
  container; pushing needs neither.

## Consequences

- "Refresh" within the TTL shows the cached answer. The response still comes from the database,
  so a webhook's update is always visible.
- After a restart the cache is empty; the first lookups per parcel go to the carrier.
- Tests must expire the cache explicitly when they want a real carrier call.

## Interview answer

_To be written by Joshua._

## Measured result

_After Phase 1: cache hit rate under the k6 script, and refresh p95 with a hit vs a miss._
