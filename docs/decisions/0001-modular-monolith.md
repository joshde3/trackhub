# 0001. Modular monolith, one jar with `api` and `worker` profiles

- Status: accepted
- Date: 2026-11-22

## Context

TrackHub has two kinds of work: answering the Android app and carrier webhooks quickly, and slow,
rate-limited background work (polling carriers, sending notifications). It is built and run by one
person, and production must fit Railway's $5 Hobby plan, where every always-on service costs memory.

## Decision

Build one Spring Boot application, split into Maven modules (`trackhub-domain`, `trackhub-carriers`,
`trackhub-app`). The API and the worker are Spring profiles in the same jar. In production both
profiles run in a single container; they can be split into two containers later without code changes.

## Alternatives rejected

- **Microservices (separate API and worker services, or one per carrier).** Adds network calls,
  service discovery, distributed transactions and a second JVM's memory, with no benefit for one
  developer and one domain.
- **One module with no internal boundaries.** Cheapest to start, but business rules end up tangled
  with Spring and JPA, and nothing stops that drift.

## Consequences

- Module boundaries are enforced by Maven (compile-time dependencies) and ArchUnit tests, not by the
  network.
- One deploy updates everything; a deploy causes a short outage while the single instance restarts.
- Background load shares CPU with API requests. We watch API p95 latency under polling load and
  split the profiles into two containers if it degrades.

## Interview answer

_To be written by Joshua._

## Measured result

_Filled in at the end of Phase 1: memory per container, API p95 latency with polling running._
