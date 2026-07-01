# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Time Matcher — a self-hosted Kotlin/Ktor service for availability checking and meeting booking (Calendly-style). The system has three main surfaces:

- **Availability engine:** pure domain logic — union of busy intervals, working-hours/override rules, slot generation with buffers and notice.
- **Event-types + booking API:** event types keyed by slug, public booking JSON API, and a self-contained HTML booking page at `GET /book/{slug}`.
- **Host-admin Telegram bot (webhook):** the host uses the bot to connect Google Calendars via an in-app OAuth flow; the bot is host-restricted by Telegram user ID. Connected-calendar credentials (refresh tokens) and all configuration (settings, event types) are stored in **Postgres**.

Google Calendar sits behind `CalendarProvider`/`CalendarWriter` ports. In `google` mode, availability unions busy across all connected calendars and bookings write to the ★ target calendar. `inmemory` (the default) requires no credentials and is used in tests and dev.

## Build, Run, Test

**The Gradle project lives in the `time-matcher/` subdirectory, not the repository root.** Run all Gradle commands from there:

```bash
cd time-matcher
./gradlew run                                   # start the server on http://0.0.0.0:8080 (H2 default)
./gradlew build                                 # compile + test + assemble
./gradlew test                                  # run all tests
./gradlew test --tests "io.vladar107.ApplicationTest"   # run a single test class
./gradlew buildFatJar                           # build a runnable fat JAR (used by Docker)
```

**Requires JDK 25.** It was installed via the keg-only Homebrew formula `openjdk@25`, so it is not on the `PATH`; Gradle needs `JAVA_HOME` pointed at it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

**Real runtime: Docker Compose (app + Postgres):**

```bash
cp .env.example .env   # fill in your values
docker compose up      # builds the image, starts Postgres + app
```

For local webhook/OAuth testing a public HTTPS URL is required. Start the cloudflared tunnel profile:

```bash
docker compose --profile tunnel up
```

Copy the cloudflared HTTPS URL into `PUBLIC_BASE_URL` in your `.env`.

**Docker image details:** the build is a multi-stage Dockerfile. Stage 1 (`build`) runs `buildFatJar`. Stage 2 (`train`) boots the fat JAR with `-XX:AOTCacheOutput` against H2 in-memory to produce `app.aot` (JDK 25 JEP 514 AOT cache, ~0.5 s cold start). Stage 3 (`runtime`) copies the JAR + cache and runs with `-XX:AOTCache`. The project `logback.xml` is copied into the image and made authoritative via `-Dlogback.configurationFile`.

**Environment variables (used by Compose and the app):**

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:h2:file:./data/timematcher` | JDBC URL; set to `jdbc:postgresql://...` in Compose |
| `DB_USER` | `sa` | DB username |
| `DB_PASSWORD` | *(empty)* | DB password |
| `CALENDAR_PROVIDER` | `inmemory` | Set to `google` to enable Google Calendar |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Shared public base URL for OAuth callback and Telegram webhook |
| `GOOGLE_CLIENT_ID` | *(empty)* | OAuth client ID |
| `GOOGLE_CLIENT_SECRET` | *(empty)* | OAuth client secret |
| `TELEGRAM_BOT_TOKEN` | *(empty)* | Bot token from BotFather |
| `TELEGRAM_HOST_USER_ID` | `0` | Telegram user ID of the host (bot ignores all others) |
| `TELEGRAM_WEBHOOK_SECRET` | *(empty)* | Webhook secret token; required for webhook registration |

**Testcontainers:** the Postgres integration test (`PostgresIntegrationTest`) requires a running Docker daemon. On Docker Desktop 29.x, add `~/.docker-java.properties` with `api.version=1.44` to fix the Testcontainers version negotiation.

Toolchain: JDK 25, Kotlin 2.4.0, Ktor 3.5.1 (Netty engine; plugin `io.ktor.plugin` 3.5.1 + server runtime, aligned by `ktor-bom`), Gradle 9.5.0 (via wrapper), Kodein DI 7.32.0, Exposed 1.0.0, Flyway 11.8.2, HikariCP 6.2.1, PostgreSQL driver 42.7.4, H2 2.3.232, Testcontainers 1.20.4. Versions are in `time-matcher/gradle.properties` and `build.gradle.kts`.

**Add new `io.ktor:*` dependencies *without* a version** — `ktor-bom` aligns them; pinning one causes mismatches. The Ktor test host is `io.ktor:ktor-server-test-host-jvm`. See `documentation/adr/20260629-modernize-build-toolchain-for-jdk-25.md`.

**Exposed v1 packages:** Exposed 1.0.0 uses `org.jetbrains.exposed.v1.*` — for example `org.jetbrains.exposed.v1.core.Table`, `org.jetbrains.exposed.v1.jdbc.Database`, `org.jetbrains.exposed.v1.jdbc.transactions.transaction`. The old `org.jetbrains.exposed.sql.*` path is dead.

Tests run with `maxParallelForks = 1` — Exposed uses a single global connection/transaction state.

**Runtime endpoints:**

| Method | Path | Description |
|---|---|---|
| `GET` | `/user` | List users |
| `POST` | `/user` | Create user |
| `GET` | `/availability/slots` | Query available slots |
| `GET /PUT` | `/availability/config` | Read / update availability rules |
| `GET` | `/event-types` | List event types |
| `POST` | `/event-types` | Create event type |
| `POST` | `/event-types/{slug}/book` | Book a slot |
| `GET` | `/book/{slug}` | Public booking page (self-contained HTML) |
| `GET` | `/oauth/google/start` | Begin Google OAuth flow (redirects to Google) |
| `GET` | `/oauth/google/callback` | Google OAuth callback (exchanges code, stores token) |
| `POST` | `/telegram/webhook/{secret}` | Telegram webhook delivery endpoint |
| `GET` | `/metrics-micrometer` | Prometheus metrics |
| `GET` | `/openapi` | Swagger UI |

Server config is in `src/main/resources/application.yaml`.

## Architecture

Layered architecture with a CQRS dispatch layer and runtime DI (Kodein). Packages under `src/main/kotlin/io/vladar107/`:

- `domain/` — plain domain entities (e.g. `User`, `ConnectedCalendar`, `EventType`). No framework dependencies.
- `application/<useCase>/` — use cases as command/query handlers, plus the **repository interfaces (ports)** they depend on. Each feature gets its own subpackage.
- `data/repositories/` — in-memory repository implementations.
- `data/persistence/` — `Database.kt` (`Db.init` / `Db.close`, HikariCP, Flyway) and `Tables.kt` (Exposed table definitions). `Db.init` is driver-agnostic: it branches on the JDBC URL prefix (`postgresql` vs `h2`), applies the H2 `CASE_INSENSITIVE_IDENTIFIERS=TRUE` shim only for H2, then runs Flyway migrations.
- `data/google/` — `GoogleCalendarApi` (raw REST freeBusy + events.insert via Ktor client), `GoogleCalendarProvider` + `GoogleCalendarWriter` (port adapters), `GoogleTokenManager` (per-calendar refresh-token cache + mutex-guarded refresh), `GoogleTokenSource` (single-token variant), `GoogleOAuthApi` (authorization URL, code exchange, account email lookup).
- `data/telegram/` — `TelegramApi` (sendMessage, answerCallback, setWebhook).
- `web/` — the Ktor boundary:
  - `web/availability/` — `/availability/*` controller
  - `web/booking/` — event-type and booking controllers; booking page
  - `web/oauth/` — `GoogleOAuthController.kt` (`/oauth/google/start`, `/oauth/google/callback`), `ConnectStateStore` (UUID nonces, single-use, 10-min TTL)
  - `web/telegram/` — `TelegramBot` (command/callback dispatch) + `configureTelegramBot` (webhook route + startup `setWebhook` call)
  - `web/user/` — user controller
  - `web/di/` — DI composition root
  - `web/plugins/` — serialization, status pages
  - `web/monitoring/` — Prometheus
  - `web/documentation/` — OpenAPI
- `infrastructure/` — CQRS primitives: `Command`/`CommandHandler` + `CommandProvider`, and `Query`/`QueryHandler` + `QueryProvider`.

### CQRS request flow

A controller resolves a `CommandProvider`/`QueryProvider` from DI and dispatches a typed command/query. The provider uses reified generics to look up the matching handler from Kodein (`di.direct.instance<CommandHandler<TResult, TParam>>()`) and invokes its `suspend handle(...)`. Handlers depend only on application-layer port interfaces, never on concrete adapters.

### Dependency injection

`Application.module()` (in `Application.kt`) calls a series of `configure*` extension functions. `configureDi()` (`web/di/ConfigureDi.kt`) is the composition root — it installs the Kodein DI container and calls:

- `configureCommands()` — bind command handlers (`ConfigureCommands.kt`)
- `configureQueries()` — bind query handlers (`ConfigureQueries.kt`)
- `configureRepositories()` — bind repository ports to implementations (`ConfigureRepositories.kt`)
- `configureExternalServices()` — bind external service clients (`ConfigureExternalServices.kt`); selects `google` vs `inmemory` calendar adapters based on `calendar.provider`

**To add a feature end to end:** define the command/query + handler (and any port interface) under `application/<useCase>/`, bind the handler in `ConfigureCommands.kt`/`ConfigureQueries.kt`, implement and bind any repository in `data/` + `ConfigureRepositories.kt`, add the controller under `web/`, and register the controller's `configure*` function in `Application.module()`.

## Documentation

- `documentation/adr/` — Architecture Decision Records, managed by [Log4brains](https://github.com/thomvaill/log4brains) (`log4brains preview`, `log4brains adr new`). Read these before reversing a tooling/architecture decision.
- `documentation/diagrams/` — C4 container diagram (Mermaid).
- `documentation/google-calendar-setup.md` — step-by-step guide for connecting Google Calendar and setting up the Telegram bot.

## Known gotchas

- `UserRepository.createUser` throws `NotImplementedError` — user persistence is not yet implemented (the user endpoint exists but creates nothing durable).
- The Shadow fat JAR task sets `duplicatesStrategy = DuplicatesStrategy.INCLUDE` and calls `mergeServiceFiles()` — this is required so `META-INF/services/` files from `flyway-database-postgresql` and `flyway-core` are merged instead of one silently overwriting the other (Shadow 9.x defaults to EXCLUDE). Do not remove these settings.
- `Db.init` re-initialises the Exposed global `Database` object on each call. With `maxParallelForks = 1` this is harmless, but it means the HikariCP pool is leaked on re-init in test code. Use unique in-memory DB names per test suite to avoid cross-test state.
- The Postgres integration test requires Docker. On Docker Desktop 29.x, Testcontainers needs `~/.docker-java.properties` containing `api.version=1.44`.
- The project `logback.xml` has `<root level="trace">` (Ktor template default) — very verbose in the container. Consider raising to `INFO` for production deployments.
