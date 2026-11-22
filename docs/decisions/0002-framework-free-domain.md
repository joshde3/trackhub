# 0002. Hexagonal core: a framework-free domain module

- Status: accepted
- Date: 2026-11-22

## Context

TrackHub's real logic is in its rules: which status changes are allowed, how late and duplicate
carrier events are merged. These rules must be easy to test thoroughly and must not change when we
swap infrastructure (Postgres job queue to RabbitMQ, Caffeine to Redis, JVM to native image).

## Decision

`trackhub-domain` is plain Java 21. It has no main dependencies at all, and an ArchUnit test
(`DomainPurityTest`) fails the build if any class in it depends on Spring, Jakarta/JPA, Hibernate,
Jackson or an HTTP client. Infrastructure talks to the domain through ports (interfaces) the domain
defines. JaCoCo enforces 80% line and branch coverage on this module.

## Alternatives rejected

- **JPA annotations on domain classes.** Less mapping code, and a fair choice for CRUD apps, but
  persistence rules (no-arg constructors, lazy loading, mutable setters) leak into business logic,
  and domain tests then need a database or heavy mocking.

## Consequences

- A persistence adapter must map JPA entities to domain objects (about 100 lines, added in PR 3).
- The domain suite runs in well under a second, so it can be run constantly while developing.

## Interview answer

_To be written by Joshua._

## Measured result

_Phase 1 end: domain coverage and domain test suite runtime._
