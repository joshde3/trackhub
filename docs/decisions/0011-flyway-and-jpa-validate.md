# 0011. Flyway owns the schema; JPA only validates it

- Status: accepted
- Date: 2026-11-22

## Context

The schema carries business rules: unique constraints decide duplicates (one user tracks a parcel
once; a scan is stored once), and a version column protects against lost updates. Those rules must
be reviewed, versioned and identical in tests and production.

## Decision

- Schema changes are hand-written SQL migrations in `trackhub-app/src/main/resources/db/migration`,
  applied by Flyway at startup. Each table arrives in the pull request that first uses it.
- Hibernate runs with `ddl-auto: validate`: it refuses to start if an entity and the schema
  disagree, but never changes the schema itself.
- Integration tests run against real Postgres 16 in Docker (Testcontainers) with the same
  migrations, never an in-memory database.
- JPA entities live in the persistence adapter and are mapped to domain objects (ADR 0002).
  Optimistic locking uses a `version` column, checked both before writing and in the UPDATE.
- `open-in-view` is off, so no query runs while a response is being rendered.

## Alternatives rejected

- **`ddl-auto: update`.** Convenient, but Hibernate guesses: it never drops or renames, can't
  express partial indexes or trigram indexes, and production changes would be unreviewed.
- **H2 in tests.** Faster to start, but it doesn't support `pg_trgm`, `GENERATED ALWAYS AS
  IDENTITY` the same way, or Postgres error messages, so tests would pass on rules production breaks.
- **Pessimistic locking (`SELECT ... FOR UPDATE`).** Would hold a row lock while calling a carrier
  API for seconds. Conflicts are rare here, so detecting them is cheaper than preventing them.

## Consequences

- Integration tests need Docker, locally and in CI (GitHub's Ubuntu runners have it).
- Every entity change needs a matching migration, or the app refuses to start, which is the point.

## Interview answer

_To be written by Joshua._
