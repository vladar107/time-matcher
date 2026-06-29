# Modernize build toolchain for JDK 25

- Status: accepted
- Deciders: vladar107
- Date: 2026-06-29
- Tags: tools, build, jvm

## Context and Problem Statement

Development should run on the latest Java LTS, JDK 25. The project pinned Gradle 8.4, which cannot run on JDK 25 (Gradle 8.4 cannot even run on JDK 21, because its embedded Kotlin runtime lacks support). Running Gradle on JDK 25 requires Gradle 9.1.0+, which in turn requires the Kotlin Gradle Plugin 2.0.0+. The Ktor Gradle plugin 2.3.9 also fails on Gradle 9 — its bundled Shadow plugin calls `Project.convention`, an API removed in Gradle 9.

## Decision Drivers

- Build and run on JDK 25 (latest LTS).
- A coherent, internally consistent dependency set.
- Keep the existing application code working.

## Considered Options

- Stay on JDK 17 and keep Gradle 8.4 / Kotlin 1.9.23 (no JDK 25).
- Bump the toolchain but force the Ktor runtime to stay on 2.3.9 (force-resolve all `io.ktor:*` to 2.3.9 against the plugin BOM).
- Adopt the Ktor 3.x runtime that the upgraded Ktor Gradle plugin already pulls in.

## Decision Outcome

Chosen option: **adopt Ktor 3.5.1**. Upgrading the Ktor Gradle plugin to 3.5.1 (required for Gradle 9) applies `io.ktor:ktor-bom:3.5.1`, which drives every unversioned `io.ktor:*` dependency to 3.5.1. The existing application code compiled unchanged against Ktor 3.5.1 and the full build (including a smoke test that boots the whole `module()` — DI, monitoring, routing) passes, so adopting Ktor 3 coherently is lower-risk than fighting the BOM to pin 2.3.9.

Concrete versions:
- JDK 25 (LTS) — installed via Homebrew formula `openjdk@25` (keg-only).
- Gradle 9.5.0 (≥ 9.1.0 for JDK 25; within the Kotlin Gradle Plugin's supported ceiling).
- Kotlin 2.4.0 (`kotlin("jvm")` + `kotlin.plugin.serialization`).
- Ktor 3.5.1 — Gradle plugin `io.ktor.plugin` 3.5.1 and the server runtime, aligned by `ktor-bom`. `ktor_version=3.5.1` in `gradle.properties`; Ktor deps are left unversioned so the BOM aligns them.
- Kodein DI 7.32.0 (bumped from 7.21.0, which predates Ktor 3) for the Ktor-3-compatible server integration.

### Migration notes

- The Ktor 2 test artifact `ktor-server-tests-jvm` was removed in Ktor 3; tests now depend on `io.ktor:ktor-server-test-host-jvm`.
- No application source changes were required — the code's Ktor usage is source-compatible across 2.x → 3.x.

### Consequences

- `JAVA_HOME` must point at the keg-only JDK 25 for Gradle to run:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.
- The Gradle daemon prints a `restricted method ... System::load` native-access warning under JDK 25; harmless, fixed by Gradle/native-platform in a later release.
- New Ktor deps should be added unversioned so `ktor-bom` keeps them aligned; do not pin individual `io.ktor:*` versions.
