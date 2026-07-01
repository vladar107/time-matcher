# Postgres as the runtime database; H2 retained for tests and AOT training

- Status: accepted
- Deciders: vladar107
- Date: 2026-07-01
- Tags: architecture, persistence, postgres, h2, docker

## Context and Problem Statement

Phase 2 introduced H2 (file-backed) as the production database. Phase 3 adds Docker. H2 is a poor fit for a containerised service: the file is inside the container (lost on rebuild), and H2 lacks production-grade connection pooling and observability. What should the runtime database be?

## Decision Drivers

- Bookings and connected-calendar credentials must survive container restarts.
- The Docker AOT training boot (Stage 2 of the Dockerfile) must be self-contained — no external Postgres at build time.
- Tests must be fast and isolated; spinning up a real Postgres per test suite adds latency.
- Migration portability: Flyway migrations should run unchanged on both H2 and Postgres.

## Considered Options

- H2 file-backed for both dev and production
- Postgres for production; H2 removed entirely (Testcontainers for all tests)
- Postgres for production; H2 retained for tests and AOT training boot

## Decision Outcome

Chosen option: **Postgres (Hikari-pooled) for runtime; H2 retained as a runtime `implementation` dependency for tests and AOT training.**

`Db.init` is driver-agnostic: it branches on the JDBC URL prefix (`postgresql` vs `h2`). For H2 it appends `CASE_INSENSITIVE_IDENTIFIERS=TRUE` so Exposed's quoted-lowercase identifiers match H2's unquoted-uppercase storage. For Postgres it connects directly. Flyway migrations use only portable SQL (no H2-specific syntax) so the same scripts run on both engines.

HikariCP manages the connection pool (`maximumPoolSize=10`). `flyway-database-postgresql` is included as a direct dependency (Flyway 11 modularised PG support) in addition to `flyway-core`.

The Postgres integration test (`PostgresIntegrationTest`) uses Testcontainers (`postgres:17`) for fidelity. The rest of the test suite runs against H2 in-memory.

### Positive Consequences

- Data persists across container restarts (Postgres volume in Compose).
- HikariCP provides connection pooling, health checking, and metrics.
- AOT training boot remains self-contained (H2 in-memory, no external service at build time).
- Tests remain fast (H2 in-memory; Testcontainers only for the PG integration test).

### Negative Consequences

- `docker compose up` is required for Postgres; `./gradlew run` still works but uses H2 (dev mode).
- The Postgres integration test requires a running Docker daemon. On Docker Desktop 29.x, Testcontainers needs `~/.docker-java.properties` with `api.version=1.44`.
- H2 stays a production `implementation` dependency (not `testImplementation`) because the AOT training stage boots with H2; it is not exercised in the production Postgres path.

## Links

- [Docker + AOT cold start ADR](20260701-docker-aot-cold-start.md)
- [Bookings live in the calendar ADR](20260629-bookings-live-in-the-calendar.md)
