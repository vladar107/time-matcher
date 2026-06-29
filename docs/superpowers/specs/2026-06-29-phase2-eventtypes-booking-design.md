# Design — Phase 2, Slice 2a: EventTypes + Booking (config on H2, calendar abstracted)

- Status: approved
- Date: 2026-06-29
- Author: vladar107
- Scope: First slice of Phase 2 — durable EventTypes and a working booking loop, building on the Phase 1 availability finder.

## Context

Phase 1 shipped a multi-calendar availability finder: a pure `AvailabilityEngine` behind a `CalendarProvider` port, with in-memory adapters, exposed over HTTP. Phase 2 turns availability into bookings.

Agreed product architecture (the Calendly shape):

- **The calendar is the system of record for bookings.** A booking is an event written to a (connected) calendar; availability is pulled live from calendars. We never store bookings in our own database.
- **The database holds only configuration**: EventTypes, connected-calendar records, settings.
- **Booking logic is in-memory / stateless** — compute open slots, validate, then write the event to the calendar.
- **Roles**: one **host** manages calendars, settings, and event types; **anyone with a link** can book (no account).

This is the whole product, so it is decomposed into slices:

- **2a (this spec)** — H2 config persistence + EventTypes + the booking loop against the calendar **ports** (in-memory calendar adapter as the event store).
- **2b (later)** — Real Google Calendar behind the calendar ports (OAuth, read busy + write event, token storage); connected-calendar CRUD.
- **2c (later)** — Host authentication + the attendee-facing booking page.
- **Phase 3 (later)** — Notifications.

## Goal

A host defines EventTypes (persisted in H2, surviving restarts). Anyone with an EventType's link sees its open slots and books one; the booking is written to the calendar (in-memory adapter for now) and immediately stops being offered. Configuration persists; bookings live in the calendar.

## Persistence stack (new dependencies — decided)

- **H2** embedded, **file mode** at runtime (no server), in-memory mode for tests.
- **Flyway** for SQL migrations (`db/migration/V1__init.sql`).
- **Exposed** (Kotlin SQL DSL): `exposed-core`, `exposed-jdbc`, `exposed-java-time`.

New dependencies: `com.h2database:h2`, `org.flywaydb:flyway-core`, `org.jetbrains.exposed:exposed-core` / `-jdbc` / `-java-time`. Versions are pinned in `gradle.properties` / `build.gradle.kts`. **Their compatibility with Gradle 9.5 / Kotlin 2.4 / JDK 25 must be verified empirically before building on them** (Phase 0 showed pinned versions can lag the toolchain); fall back to the latest compatible versions and record any adjustment.

## Config / settings split

Phase 1 bundled everything into `AvailabilityRules`. Phase 2 splits it:

- **Settings** (host-global, single record): `zone`, `granularityMinutes`, `minimumNoticeMinutes`, weekly working hours, date overrides.
- **EventType** (per meeting type): `duration`, `bufferBefore`, `bufferAfter` (buffers move here — the data model's `gap_before/gap_after`).

Finding slots for an EventType assembles an effective `AvailabilityRules` from **Settings + that EventType's duration/buffers**, then calls the **unchanged Phase-1 `AvailabilityEngine`**. The engine is not modified; only the assembly of its input changes.

## H2 schema (Flyway `V1__init.sql`) — configuration only

- `settings` — singleton: `zone`, `granularity_minutes`, `minimum_notice_minutes`.
- `working_hours` — `day_of_week`, `start_time`, `end_time` (recurring weekly hours).
- `date_override` — `date`, `start_time`, `end_time` (nullable times = day unavailable).
- `event_type` — `id` (uuid), `slug` (unique; the booking link), `name`, `duration_minutes`, `buffer_before_minutes`, `buffer_after_minutes`, `status` (ACTIVE/INACTIVE).
- `connected_calendar` — `id`, `name`, `provider`, `created_at`; seeded with one default `IN_MEMORY` calendar. Full management arrives in 2b with real providers.

Booked events are **not** stored here — they live in the calendar.

## Architecture — ports & the calendar

- `CalendarProvider.busyIntervals(window): List<BusyInterval>` — unchanged (read busy).
- **New** `CalendarWriter.createEvent(calendarId, event)` — writes a booked event (title, attendee, interval). The in-memory calendar adapter stores events and derives `busyIntervals` from them. Phase 1's manual `addBusy` becomes a titleless event. **A real Google `CalendarProvider`/`CalendarWriter` drops in here in 2b with no booking-logic change.**
- **New** DB-backed repository ports (application layer), implemented by Exposed adapters (data layer): `SettingsRepository`, `EventTypeRepository`, `ConnectedCalendarRepository`. These replace Phase 1's in-memory `AvailabilityConfigRepository` (settings now persist).

## Booking flow (the new logic — in-memory, stateless)

1. `GET /book/{slug}/slots?from&to` (public) → open slots for that EventType (effective rules = Settings + EventType duration/buffers, via the engine).
2. `POST /book/{slug}` (public), body `{ attendeeName, attendeeEmail, start }`:
   1. Load the active EventType by slug — `404` if missing or inactive.
   2. **Re-validate** that the chosen `start` is genuinely open *now* — recompute open slots against live calendar busy + settings, and confirm `start` is among them. This is the double-booking guard.
   3. Create the Event and `CalendarWriter.createEvent(...)` it onto the host's connected calendar. In Slice 2a this is the single seeded `IN_MEMORY` calendar; choosing among multiple calendars is a 2b concern.
   4. Return `201` with the booking, or `409 Conflict` if the slot was taken in the meantime.

**Double-booking prevention:** the validate-then-write sequence runs under a lock in the booking service, so two concurrent bookings cannot claim the same slot (in-memory, single process — sufficient for Slice 2a; revisited when persistence/concurrency grows).

The booked event becomes busy because it is stored in the same in-memory calendar that `busyIntervals` reads — the next `slots` call excludes it.

## Endpoints & roles

- **Host (configuration):**
  - `PUT /availability/config` — now persists Settings to H2. Its DTO **loses the buffer fields** (buffers are per-EventType); it carries zone, granularity, minimum notice, weekly hours, overrides.
  - `POST /event-types`, `GET /event-types`, `GET /event-types/{slug}` — create / list / get. (EventType update & delete are deferred.)
- **Public (booking):** `GET /book/{slug}/slots`, `POST /book/{slug}`.
- **Carried over from Phase 1:** `GET /availability/slots` (generic finder — uses Settings with zero buffers and the `duration` query param, not tied to an EventType), `POST /calendars/{calendarId}/busy` (manual busy seeding).
- **Auth:** real host authentication is **deferred to Slice 2c**. Slice 2a keeps host vs. public routes cleanly separated; the EventType `slug` is the booking capability (knowing the link is what lets you book). No auth gate is added in 2a.

## Testing

Integration-weighted (`testApplication`), with focused unit tests for the genuinely new logic:

- **Integration:** create an EventType → `GET /book/{slug}/slots` returns slots → `POST /book/{slug}` succeeds (201) → the booked slot is absent from the next `slots` call. A second booking of the same slot → `409`. Unknown/inactive slug → `404`. Settings persist across a repository reload (write via `PUT /availability/config`, read back). Use H2 in-memory mode for tests.
- **Unit:** the Settings + EventType → `AvailabilityRules` assembly (correct merge of zone/working-hours/overrides with per-type duration/buffers); the double-booking guard (concurrent/repeated booking of one slot yields exactly one success).

## Explicitly out of scope (later slices)

Real Google/CalDAV calendar integration & OAuth (2b) · connected-calendar CRUD and multi-calendar selection (2b) · host authentication/login and the attendee-facing booking page/UI (2c) · notifications (Phase 3) · EventType update/delete and `additional_questions` · reschedule/cancel of bookings · persisting bookings in our own DB (by design, they live in the calendar).
