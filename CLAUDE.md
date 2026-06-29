# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Time Matcher — a Kotlin/Ktor service for checking availability and creating meetings. Early-stage: only user creation is partially scaffolded; most of the data model in `documentation/diagrams/Data-Model.md` (Attendee, Calendar, EventType, Event) is aspirational and not yet implemented.

## Build, Run, Test

**The Gradle project lives in the `time-matcher/` subdirectory, not the repository root.** Run all Gradle commands from there:

```bash
cd time-matcher
./gradlew run                                   # start the server on http://0.0.0.0:8080
./gradlew build                                 # compile + test + assemble
./gradlew test                                  # run all tests
./gradlew test --tests "io.vladar107.ApplicationTest"   # run a single test class
./gradlew buildFatJar                           # build a runnable fat JAR (Ktor plugin)
```

**Requires JDK 25.** It was installed via the keg-only Homebrew formula `openjdk@25`, so it is not on the `PATH`; Gradle needs `JAVA_HOME` pointed at it:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Toolchain: JDK 25 (LTS), Kotlin 2.4.0, Ktor 3.5.1 (Netty engine; plugin `io.ktor.plugin` 3.5.1 + server runtime, aligned by `ktor-bom`), Gradle 9.5.0 (via wrapper), Kodein DI 7.32.0. Versions are in `time-matcher/gradle.properties` and `build.gradle.kts`. **Add new `io.ktor:*` dependencies *without* a version** — `ktor-bom` aligns them; pinning one causes mismatches. The Ktor test host is `io.ktor:ktor-server-test-host-jvm`. See `documentation/adr/20260629-modernize-build-toolchain-for-jdk-25.md`.

Runtime endpoints: `/user` (GET/POST), `/metrics-micrometer` (Prometheus), `/openapi` (Swagger UI). Server config is in `src/main/resources/application.yaml`.

## Architecture

Layered architecture with a CQRS dispatch layer and runtime DI (Kodein). Packages under `src/main/kotlin/io/vladar107/`:

- `domain/` — plain domain entities (e.g. `User`). No framework dependencies.
- `application/<useCase>/` — use cases as command/query handlers, plus the **repository interfaces (ports)** they depend on. Each feature gets its own subpackage (e.g. `userCreation/`).
- `data/repositories/` — repository **implementations (adapters)** of the application-layer ports.
- `web/` — the Ktor boundary: controllers (`web/user/`), DTOs (`web/.../dto/`), DI wiring (`web/di/`), plugins (`web/plugins/`), monitoring, and OpenAPI docs.
- `infrastructure/` — CQRS primitives: `Command`/`CommandHandler` + `CommandProvider`, and `Query`/`QueryHandler` + `QueryProvider`.

### CQRS request flow

A controller resolves a `CommandProvider`/`QueryProvider` from DI and dispatches a typed command/query. The provider uses reified generics to look up the matching handler from Kodein (`di.direct.instance<CommandHandler<TResult, TParam>>()`) and invokes its `suspend handle(...)`. Handlers depend only on application-layer port interfaces, never on concrete adapters.

### Dependency injection

`Application.module()` (in `Application.kt`) calls a series of `configure*` extension functions. `configureDi()` (`web/di/ConfigureDi.kt`) is the composition root — it installs the Kodein DI container and calls:

- `configureCommands()` — bind command handlers (`ConfigureCommands.kt`)
- `configureQueries()` — bind query handlers (`ConfigureQueries.kt`)
- `configureRepositories()` — bind repository ports to implementations (`ConfigureRepositories.kt`)
- `configureExternalServices()` — bind external service clients (`ConfigureExternalServices.kt`)

**To add a feature end to end:** define the command/query + handler (and any port interface) under `application/<useCase>/`, bind the handler in `ConfigureCommands.kt`/`ConfigureQueries.kt`, implement and bind any repository in `data/repositories/` + `ConfigureRepositories.kt`, add the controller under `web/`, and register the controller's `configure*` function in `Application.module()`.

## Documentation

- `documentation/adr/` — Architecture Decision Records, managed by [Log4brains](https://github.com/thomvaill/log4brains) (`log4brains preview`, `log4brains adr new`). Read these before reversing a tooling/architecture decision (e.g. the choice of Kodein for DI).
- `documentation/diagrams/` — C4 container diagram and the target ER data model (Mermaid).

## Known gotchas

- `src/test/kotlin/.../ApplicationTest.kt` is a leftover Ktor template test that imports `web.plugins.configureRouting` (which does not exist) and asserts a `/` "Hello World!" route — it will not compile/pass against the current code. Replace it rather than building on it.
- `UserRepository.createUser` throws `NotImplementedError` — there is no persistence layer yet.
