# Bookings live in the calendar; the DB holds only configuration

- Status: accepted
- Deciders: vladar107
- Date: 2026-06-29
- Tags: architecture, booking, persistence

## Context and Problem Statement
Phase 2 introduces booking. We must decide where bookings and configuration live.

## Decision Outcome
The **calendar is the system of record for bookings** — a booking is an event written to a connected calendar (via the `CalendarWriter` port; in-memory adapter in slice 2a, real Google in 2b), and availability is pulled live from calendars. The **database (H2 file, via Exposed + Flyway) holds only configuration**: settings, event types, connected-calendar records. **Booking logic is in-memory/stateless**: compute open slots, validate under a Mutex, then write the event.

Consequences:
- The Phase-1 `AvailabilityEngine` is unchanged; its `AvailabilityRules` is assembled from host-global `Settings` + a per-`EventType` duration/buffers.
- Double-booking is prevented by serializing validate-then-write with a Mutex (sufficient for a single-process, single-host slice).
- Roles: one host manages settings/event types; anyone with an event type's `slug` (the link) can book. Host authentication is deferred to slice 2c.
- Config persists in an H2 file (`db.url`); tests override it to a unique in-memory DB. `Db.init` connects Exposed globally, so tests run with `maxParallelForks = 1`.
