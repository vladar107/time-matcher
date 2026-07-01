# Telegram updates delivered via webhook instead of long-polling

- Status: accepted
- Deciders: vladar107
- Date: 2026-07-01
- Tags: architecture, telegram, webhook, scalability

## Context and Problem Statement

The Telegram bot needs to receive updates from Telegram. Two delivery models exist: long-polling (the app calls `getUpdates` repeatedly) and webhooks (Telegram POSTs each update to a registered URL). Which should be used?

## Decision Drivers

- The service is intended to run in Docker on a container host where scale-to-zero is a desirable property — long-polling requires a persistent background coroutine and prevents scale-to-zero.
- Cold-start latency matters when the container is not running (AOT cache targets ~0.5 s); a webhook naturally fits request-driven execution.
- The OAuth callback already requires a public HTTPS URL (`PUBLIC_BASE_URL`), so the webhook registration reuses the same URL.
- Security: webhook updates must not be spoofable by third parties.

## Considered Options

- Long-polling (`getUpdates` loop in a background coroutine)
- Webhook (Telegram POSTs to `POST /telegram/webhook/{secret}`)

## Decision Outcome

Chosen option: **webhook delivery**, because it is request-driven, supports scale-to-zero, and shares the public URL already needed for the OAuth callback.

The webhook route is `POST /telegram/webhook/{secret}`. Access is fail-closed: the handler checks both the path secret and the `X-Telegram-Bot-Api-Secret-Token` header; a mismatch returns 403. Malformed JSON returns 400. On startup, when `telegram.webhookSecret` and `telegram.botToken` are both configured, the app calls `TelegramApi.setWebhook` with `drop_pending_updates=true` (best-effort, errors are logged but do not crash the app).

The webhook route is always registered (so the in-memory test suite boots cleanly without telegram credentials), but the startup `setWebhook` call is skipped when the secret or token is absent.

### Positive Consequences

- No persistent background coroutine — the app can scale to zero between updates.
- AOT cache cold start (~0.5 s) is acceptable for webhook workloads.
- Security: dual secret check (path + header) makes spoofed updates effectively impossible.

### Negative Consequences

- A public HTTPS URL is required at startup for webhook registration; local development without a tunnel (e.g. cloudflared) cannot receive live updates.
- If the app is offline during a Telegram update, Telegram will retry — `drop_pending_updates=true` clears the queue on each (re)start.

## Links

- [Telegram host-admin bot ADR](20260701-telegram-host-admin-bot.md)
- [Docker + AOT cold start ADR](20260701-docker-aot-cold-start.md)
