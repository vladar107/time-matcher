# Design — Phase 1, Slice 1: Multi-Calendar Availability Finder

- Status: approved
- Date: 2026-06-29
- Author: vladar107
- Scope: First buildable slice of the Time Matcher "find a spot" feature.

## Context

Time Matcher is being built as a self-hosted Calendly analog. The agreed roadmap:

- **Phase 1 (this spec):** multi-calendar aggregation + availability engine — "given my calendars and my rules, find open slots."
- **Phase 2 (later):** event types + booking (attendees, events).
- **Phase 3 (later):** attendee-facing links + notifications (Telegram bots, per the C4 diagram).

The "finder" is the shared core and the most testable part, so it is built first. Each phase keeps the existing project style: layered architecture, CQRS dispatch, Kodein DI, ADRs, and diagram updates.

This slice deliberately defers all external integration and persistence: busy times come from a `CalendarProvider` **port** with a trivial in-memory adapter, so the availability engine can be built and fully exercised without OAuth, a database, or new dependencies. A real `GoogleCalendarProvider` and a database adapter become later slices behind the same ports, with no engine changes.

## Goal

`GET /availability/slots?from=…&to=…&duration=PT30M` returns open meeting slots, computed by subtracting busy times (aggregated across multiple calendars) from recurring working hours, honoring buffers, minimum notice, and date-specific overrides. Working and tested end to end, in-memory only.

## Architecture

Layered + CQRS + Kodein, ports first. New packages under `src/main/kotlin/io/vladar107/`:

```
domain/availability/          pure domain, no framework deps
  TimeInterval                value object: half-open [start, end) of java.time.Instant;
                              overlaps / subtract / merge / duration helpers
  BusyInterval                TimeInterval + calendarId (source)
  LocalTimeRange              half-open [start, end) of java.time.LocalTime within a day
  WeeklyAvailability          Map<DayOfWeek, List<LocalTimeRange>>  (multiple ranges/day → lunch breaks)
  DateOverride                LocalDate -> replacement List<LocalTimeRange> (empty list = unavailable)
  AvailabilityRules           zone: ZoneId, weekly: WeeklyAvailability, overrides: List<DateOverride>,
                              granularity: Duration, bufferBefore: Duration, bufferAfter: Duration,
                              minimumNotice: Duration
  SlotSearch                  from: Instant, to: Instant, duration: Duration
  AvailabilityEngine          findSlots(rules, busy, search, now): List<TimeInterval>   ← pure, the heart

application/availability/     ports + use cases
  CalendarProvider (port)             suspend busyIntervals(window: TimeInterval): List<BusyInterval>
                                      (implementation aggregates/unions across all calendars)
  AvailabilityConfigRepository (port) suspend load(): AvailabilityRules ; suspend save(rules)
  FindAvailableSlotsQuery + Handler   read side (fills the empty configureQueries)
  AddBusyBlockCommand + Handler       seed a calendar's busy blocks
  SetAvailabilityRulesCommand + Handler set working hours / rules

data/repositories/            adapters
  InMemoryCalendarProvider              busy blocks per calendarId; busyIntervals() unions across calendars,
                                        returns those overlapping the window
  InMemoryAvailabilityConfigRepository  holds one AvailabilityRules; seeded default (Mon–Fri 09:00–17:00)

web/availability/             Ktor boundary
  GET  /availability/slots            -> FindAvailableSlotsQuery
  POST /calendars/{calendarId}/busy   -> AddBusyBlockCommand
  PUT  /availability/config           -> SetAvailabilityRulesCommand
  dto/                                 ISO-8601 string fields (no custom serializers)
  configureAvailability()              registered in Application.module()
```

### Request flow

Controllers resolve `CommandProvider` / `QueryProvider` from Kodein and dispatch typed commands/queries, exactly as the existing `UserController` does. `FindAvailableSlotsQueryHandler` depends only on the `CalendarProvider` and `AvailabilityConfigRepository` ports plus an injected `java.time.Clock`; it loads rules, fetches busy intervals for the window, runs `AvailabilityEngine`, and returns the slots.

## Engine algorithm

`AvailabilityEngine.findSlots(rules, busy, search, now)`:

1. Effective start = `max(search.from, now + rules.minimumNotice)`; end = `search.to`. If start ≥ end, return empty.
2. For each `LocalDate` in `[start, end]` resolved in `rules.zone`: take that date's `DateOverride` ranges if one exists, otherwise `rules.weekly[dayOfWeek]` (absent/empty → no availability that day).
3. Project each `LocalTimeRange` to an absolute `TimeInterval` using `rules.zone`; clip each to `[effectiveStart, end]`. Collect → working intervals.
4. Expand each busy interval by `[−bufferBefore, +bufferAfter]`, then merge overlapping/adjacent busy intervals.
5. Subtract merged busy from working intervals → free intervals.
6. Build a slot grid: candidate start times lie on a grid of step `rules.granularity`, anchored at the start of that day's earliest working range in `rules.zone` (so a 09:00 start with 30-min granularity yields 09:00, 09:30, … — clean local times, not times offset by where a meeting happened to end). Within each free interval, emit every grid-aligned start whose `[start, start + duration)` fits entirely inside that free interval.
7. Return slots sorted ascending. The DTO renders them in `rules.zone`.

DST transitions and multi-range days are handled by `java.time` defaults; this is acceptable for Phase 1 and noted in the ADR.

## Key decisions

- **`java.time` only — no new dependency.** Consistent with the existing `java.util.UUID` usage. kotlinx-datetime was considered and rejected to avoid adding a library; revisit if it causes friction.
- **DTO time fields are ISO-8601 strings**, parsed and validated at the web boundary. This avoids custom kotlinx serializers for `Instant`/`LocalTime`. Boundary validation: `from < to`, `duration > 0`, parseable zone/instants; invalid input → 400.
- **Inject `java.time.Clock`** through Kodein so "now" (and minimum-notice clipping) is deterministic under test. Bind to `Clock.systemDefaultZone()` in production.
- **Stateful in-memory adapters are bound as Kodein `singleton`, not `provider`.** The existing `ConfigureRepositories` uses `provider {}`, which constructs a fresh instance per resolution and would discard in-memory state between requests. New stateful bindings must use `singleton {}`.
- **Single-user for now.** `AvailabilityConfigRepository.load()` takes no user key; calendars are identified by a free-form `calendarId` string. Multi-user/auth is a later phase.

## Housekeeping (in scope)

- **Replace `src/test/kotlin/io/vladar107/ApplicationTest.kt`.** It is leftover Ktor template code importing a nonexistent `web.plugins.configureRouting` and asserting a `/` "Hello World!" route, so the build is currently red. A green build is a precondition for "working," so it is replaced with a real smoke/integration test.
- **Out of bounds:** the unrelated `CreatUserCommand` typo and the misplaced `toDTO` extension inside `web/user/dto/User.kt` are left untouched — not part of this task.

## Documentation

- **One ADR** under `documentation/adr/`: "Availability as a pure domain engine behind calendar-source ports; in-memory + java.time for Phase 1" — records why no DB, OAuth, or new dependencies yet, and the singleton-binding requirement.
- **Light diagram update** under `documentation/diagrams/` noting the Phase-1 availability component and the deferred phases.

## Testing

Weighted toward integration tests; unit tests reserved for tricky pure-logic edge cases. Happy paths + tricky edge cases, not exhaustive permutations.

- **Integration tests (`testApplication`, primary):** full-stack happy paths — set rules via `PUT /availability/config`, add busy blocks across two calendars via `POST /calendars/{id}/busy`, then assert `GET /availability/slots` returns the correct open slots; plus a couple of realistic variations (a busy block splitting a working day; an empty/closed day).
- **Engine unit tests (focused, the tricky edge cases only):** DST transition day; buffer expansion merging two adjacent busy blocks; minimum-notice clipping the window start; a `DateOverride` replacing the weekday hours (and an override marking a day unavailable); granularity alignment; the slot-must-fully-fit boundary (a gap one minute too short yields no slot); multi-calendar union.
- **Handler test:** `FindAvailableSlotsQueryHandler` against the in-memory provider with a fixed `Clock`.

## Explicitly out of scope (later phases)

Google/CalDAV sync and OAuth · real database/persistence · booking, attendees, event types (Phase 2) · notifications / Telegram bots (Phase 3) · multi-user and authentication · max-meetings-per-day (deferred to Phase 2, where bookings exist to count).
