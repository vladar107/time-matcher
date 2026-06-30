# Google Calendar integration via a single-user refresh token + raw REST

- Status: accepted
- Deciders: vladar107
- Date: 2026-06-29
- Tags: integration, calendar, google

## Context and Problem Statement

Phase 2b connects the host's real Google Calendar behind the `CalendarProvider`/`CalendarWriter` ports.

## Decision Outcome

This is a self-hosted single-host tool, so we skip an in-app OAuth web flow: the host obtains a **refresh token once** (OAuth Playground) and configures it. The app uses raw REST via the Ktor HTTP client for three calls — token refresh, `freeBusy.query` (read busy), `events.insert` with `sendUpdates=all` (write the booking + invite the attendee) — rather than the heavy Google Java client library. The adapter is selected by `calendar.provider=google`; `inmemory` stays the default. Secrets live in config/env, never git or the DB. Calendar failures surface as HTTP 502.

### Consequences

- No consent screen / callback endpoint / multi-account support (single host); add an OAuth web flow later if multi-user is needed.
- Primary calendar only for this slice; multi-calendar free/busy aggregation deferred.
- Tokens are not persisted in the DB (config/env only); revisit if multiple connected calendars arrive.
- Google adapters take an injectable `HttpClient`, unit-tested with Ktor `MockEngine`; one manual real-calendar check verifies the live path.
