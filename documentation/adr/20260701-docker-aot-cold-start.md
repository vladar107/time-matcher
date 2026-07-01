# Multi-stage Docker image with JDK 25 AOT cache for fast cold start

- Status: accepted
- Deciders: vladar107
- Date: 2026-07-01
- Tags: architecture, docker, performance, jvm, aot

## Context and Problem Statement

The service runs as a Docker container. JVM cold start is typically 2–4 seconds, which is noticeable on scale-to-zero hosts and slows webhook response after a cold wake-up. How can cold start be minimised without abandoning the JVM?

## Decision Drivers

- Webhook delivery requires a reasonably fast cold start (Telegram retries are bounded).
- JDK 25 ships JEP 514 (AOT class-loading cache) — the AOT cache must be trained on the same JVM/image base as the runtime to be effective.
- The fat JAR (Shadow plugin) must correctly bundle all service-provider files so Flyway can load both H2 and Postgres drivers.
- The project's `logback.xml` must win over any transitive logback config packaged in dependency JARs.

## Considered Options

- Single-stage image (no AOT)
- Multi-stage image with JDK 25 AOT cache (JEP 514)
- GraalVM native image

## Decision Outcome

Chosen option: **multi-stage Docker image with JDK 25 AOT cache**, because it delivers ~0.5 s cold start with no code changes (pure JVM, no native-image constraints) using tooling already in JDK 25.

**Stage 1 — build (`eclipse-temurin:25-jdk`):** runs `./gradlew --no-daemon buildFatJar` to produce the fat JAR.

**Stage 2 — train (`eclipse-temurin:25-jre`):** boots the fat JAR with `-XX:AOTCacheOutput=/train/app.aot` against H2 in-memory and dummy Google credentials (no external services at build time). After ~2 s the JVM receives `SIGTERM`, writes `app.aot`, and the stage verifies the file exists. The train base must be the same JRE as the runtime stage so that module layout (and therefore the AOT cache) matches.

**Stage 3 — runtime (`eclipse-temurin:25-jre`):** copies `app.jar`, `app.aot`, and `logback.xml`. Runs as a non-root user (`app`, uid 10001). Entrypoint: `java -Dlogback.configurationFile=/app/logback.xml -XX:AOTCache=/app/app.aot -jar /app/app.jar`.

**Shadow fat JAR fix:** Shadow 9.x sets `DuplicatesStrategy.EXCLUDE` by default, which causes `mergeServiceFiles()` to silently drop duplicate `META-INF/services/` entries before the transformer can merge them. This breaks Flyway's H2 and Postgres SPI registration. Fix: `duplicatesStrategy = DuplicatesStrategy.INCLUDE` + `mergeServiceFiles()` on the `ShadowJar` task. See [Shadow issue #1348](https://github.com/johnrengelman/shadow/issues/1348).

**Logback authority fix:** `ktor-server-swagger-jvm` transitively pulls in `swagger-codegen-generators`, which packages its own `logback.xml` with `<root level="error">`. With `duplicatesStrategy=INCLUDE` both files land in the fat JAR and the transitive one wins by classpath order, suppressing all INFO output. Fix: pass `-Dlogback.configurationFile=/app/logback.xml` so the JVM always loads the project's config regardless of classpath order.

**docker-compose.yml** provides `db` (postgres:17 with volume + healthcheck), `app` (built from `./time-matcher`, depends on `db` healthy, all env vars forwarded), and `cloudflared` (Cloudflare tunnel, optional `tunnel` profile) for local webhook/OAuth testing.

### Positive Consequences

- ~0.5 s cold start observed in Docker (verified via `-Xlog:aot` and Ktor startup logs).
- No code changes or native-image constraints.
- AOT training is self-contained (H2 in-memory) — no external Postgres or Google credentials at build time.
- Project logback config is authoritative (correct pattern + INFO log level control).

### Negative Consequences

- The Docker build requires two JVM startups (build + train), adding ~30 s to `docker build`.
- H2 must remain an `implementation` dependency (not `testImplementation`) so the training boot has it on the classpath.
- `app.aot` is tied to the specific JRE build; any JRE upgrade requires a full rebuild.

## Links

- [Postgres over H2 ADR](20260701-postgres-over-h2.md)
- [Webhook delivery ADR](20260701-webhook-delivery.md)
