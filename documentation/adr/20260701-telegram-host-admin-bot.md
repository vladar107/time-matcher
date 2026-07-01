# Telegram host-admin bot as the management surface

- Status: accepted
- Deciders: vladar107
- Date: 2026-07-01
- Tags: architecture, telegram, oauth, administration

## Context and Problem Statement

Time Matcher needs a management surface so the host can connect Google Calendars, choose a booking target, and manage connected calendars. How should the host authenticate and trigger the Google OAuth flow without adding a web-based admin UI?

## Decision Drivers

- The service is single-host (one owner), so a full multi-user admin UI is unnecessary overhead.
- Connecting a Google Calendar requires a browser OAuth hop — the app must initiate it and receive the callback.
- Refresh tokens must be persisted across restarts; storing them only in environment variables cannot support multiple calendars.
- The management surface should be low-friction and require no extra web page.

## Considered Options

- Web-based admin panel (password-protected page)
- Host-only Telegram bot driving an in-app OAuth flow
- CLI script that obtains a refresh token via OAuth Playground (previous Phase 2b approach)

## Decision Outcome

Chosen option: **host-only Telegram bot driving an in-app OAuth flow**, because it is low-friction (the host already uses Telegram), requires no admin UI, and naturally handles the browser hop without extra infrastructure.

The bot is restricted by `telegram.hostUserId` — all updates from other Telegram users are silently ignored. Commands: `/connect` (starts OAuth), `/calendars` (lists connected calendars with inline buttons to set the ★ booking target or remove). The `ConnectStateStore` issues single-use UUID nonces (10-minute TTL) so the OAuth `state` parameter cannot be replayed.

The OAuth flow: `/connect` generates a nonce → bot sends a link to `/oauth/google/start?state={nonce}` → host opens browser → Google consent → `/oauth/google/callback` consumes the nonce, exchanges the code for tokens, calls `ConnectGoogleCalendarCommand` to persist the refresh token in the DB, then the bot notifies the host.

### Positive Consequences

- No admin UI to build or secure.
- Multiple calendars supported (each gets its own DB row with refresh token).
- Refresh tokens survive restarts (stored in Postgres via the connected-calendar table).
- OAuth state forgery prevented by single-use nonces.

### Negative Consequences

- Requires a public HTTPS URL (`PUBLIC_BASE_URL`) for the OAuth callback, so purely local `./gradlew run` cannot complete the OAuth flow without a tunnel (e.g. cloudflared).
- The live OAuth round-trip (real Google code exchange) can only be verified manually.

## Links

- Supersedes [Google Calendar integration via a single-user refresh token + raw REST](20260629-google-calendar-via-refresh-token.md) (the env-token approach is replaced by OAuth-via-bot with DB storage)
- [Webhook delivery ADR](20260701-webhook-delivery.md)
- [Postgres over H2 ADR](20260701-postgres-over-h2.md)
