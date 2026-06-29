# Availability as a pure engine behind calendar-source ports

- Status: accepted
- Deciders: vladar107
- Date: 2026-06-29
- Tags: architecture, availability

## Context and Problem Statement

Phase 1 of Time Matcher must find open meeting slots across multiple calendars. We need calendar data and persistence, but want to build and test the core logic without committing to OAuth or a database yet.

## Decision Outcome

The availability logic lives in a pure domain `AvailabilityEngine` (no I/O). Calendar busy times and availability configuration are reached through application-layer ports (`CalendarProvider`, `CalendarBusyWriter`, `AvailabilityConfigRepository`). Phase 1 ships in-memory adapters; a Google Calendar adapter and a database adapter slot in later behind the same ports with no engine changes.

Consequences:
- Time handling uses `java.time` only — no new dependencies.
- Stateful in-memory adapters are bound as Kodein `singleton` (not `provider`), or per-request state would be lost.
- DST and multi-range days are delegated to `java.time` defaults, which is acceptable for Phase 1.
- `max-meetings-per-day` is deferred to Phase 2, where bookings exist to count.
