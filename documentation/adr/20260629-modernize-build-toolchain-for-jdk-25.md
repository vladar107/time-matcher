# Modernize build toolchain for JDK 25

- Status: accepted
- Deciders: vladar107
- Date: 2026-06-29
- Tags: tools, build, jvm

## Context and Problem Statement

Development should run on the latest Java LTS, JDK 25. The project pinned Gradle 8.4, which cannot run on JDK 25 (Gradle 8.4 cannot even run on JDK 21, because its embedded Kotlin runtime lacks support). Running Gradle on JDK 25 requires Gradle 9.1.0+, which in turn requires the Kotlin Gradle Plugin 2.0.0+. The Ktor Gradle plugin 2.3.9 also fails on Gradle 9 — its bundled Shadow plugin calls `Project.convention`, an API removed in Gradle 9.

## Decision Drivers

- Build and run on JDK 25 (latest LTS).
- Minimize churn — avoid a framework major-version migration if possible.
- Keep the existing application code working without changes.

## Considered Options

- Stay on JDK 17 and keep Gradle 8.4 / Kotlin 1.9.23 (no JDK 25).
- Full modernization: upgrade JDK, Gradle, Kotlin, AND migrate Ktor 2 → 3 (+ Kodein).
- Minimal toolchain bump: JDK 25 + Gradle 9.5.0 + Kotlin 2.4.0 + Ktor Gradle plugin 3.5.1, keeping the Ktor runtime at 2.3.9.

## Decision Outcome

Chosen option: **minimal toolchain bump**. Empirically verified that the Ktor *runtime* 2.3.9 compiles and runs unchanged under Kotlin 2.4.0 / Gradle 9.5.0, and that bumping only the Ktor *Gradle plugin* to 3.5.1 (it bundles a Gradle-9-compatible Shadow) restores `buildFatJar` and `run`. No application-code migration was required.

Concrete versions:
- JDK 25 (LTS) — installed via Homebrew formula `openjdk@25` (keg-only).
- Gradle 9.5.0 (within the Kotlin Gradle Plugin's supported ceiling; ≥ 9.1.0 for JDK 25).
- Kotlin 2.4.0 (`kotlin("jvm")` + `kotlin.plugin.serialization`).
- Ktor Gradle plugin `io.ktor.plugin` 3.5.1; Ktor server runtime stays 2.3.9.

### Consequences

- `JAVA_HOME` must point at the keg-only JDK 25 for Gradle to run:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.
- A future migration of the Ktor runtime to 3.x (with the matching Kodein-ktor version) remains an open, separate decision — deferred until there is a reason to take Ktor 3 features.
- The Gradle daemon prints a `restricted method ... System::load` native-access warning under JDK 25; harmless, fixed by Gradle/native-platform in a later release.
