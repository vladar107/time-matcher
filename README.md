# Time Matcher

A self-hosted Calendly-style availability and booking service. The host exposes event types with a public booking page; attendees pick a slot and it lands in the host's Google Calendar. A host-only Telegram bot handles all management — connecting Google Calendars, setting a booking target, and managing availability — via an in-app OAuth flow, with credentials stored in Postgres.

**Stack:** Kotlin / Ktor · Exposed + Flyway · Postgres (HikariCP) · JDK 25 AOT cache (~0.5 s cold start) · Docker Compose

---

## Quickstart

### Option A — Docker Compose (recommended)

Requires Docker and a public HTTPS URL for the Telegram webhook and Google OAuth callback.

```bash
cp .env.example .env
# Edit .env: fill in DB_PASSWORD, PUBLIC_BASE_URL, GOOGLE_*, TELEGRAM_*
docker compose up --build
```

For local testing without a public server, start the bundled cloudflared tunnel:

```bash
docker compose --profile tunnel up --build
# Copy the printed https://*.trycloudflare.com URL into PUBLIC_BASE_URL in .env, then restart
```

See [`documentation/google-calendar-setup.md`](documentation/google-calendar-setup.md) for full setup steps (Google Cloud project, OAuth client, BotFather, `.env` values).

### Option B — dev mode (no Docker, no Postgres)

Requires JDK 25 (`JAVA_HOME` must point at it).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
cd time-matcher
./gradlew run          # starts on http://0.0.0.0:8080 with H2 (in-memory mode)
```

---

## Key endpoints

Primary routes (a legacy `/user` scaffold also exists but is not implemented):

| Method | Path | Description |
|---|---|---|
| `GET` | `/book/{slug}` | Public booking page (self-contained HTML) |
| `POST` | `/event-types/{slug}/book` | Book a slot (JSON API) |
| `GET` | `/availability/slots` | Query available time slots |
| `PUT` | `/availability/config` | Update availability rules (working hours, granularity, timezone) |
| `GET` | `/event-types` | List event types |
| `POST` | `/event-types` | Create event type |
| `POST` | `/telegram/webhook/{secret}` | Telegram webhook (host bot) |
| `GET` | `/oauth/google/start` | Begin Google OAuth flow |
| `GET` | `/oauth/google/callback` | Google OAuth callback |
| `GET` | `/metrics-micrometer` | Prometheus metrics |
| `GET` | `/openapi` | Swagger UI |

---

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — build/run/test commands, architecture overview, known gotchas (for Claude Code and contributors)
- [`documentation/google-calendar-setup.md`](documentation/google-calendar-setup.md) — step-by-step Google Cloud + BotFather + Docker setup guide
- [`documentation/adr/`](documentation/adr/) — Architecture Decision Records (managed by Log4brains)
- [`documentation/diagrams/Container.md`](documentation/diagrams/Container.md) — C4 container diagram (Mermaid)
