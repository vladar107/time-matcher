# Availability Finder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Phase 1 of Time Matcher — a multi-calendar availability finder where `GET /availability/slots` returns open meeting slots computed by subtracting busy times (aggregated across calendars) from recurring working hours, honoring buffers, minimum notice, and date overrides.

**Architecture:** Pure domain `AvailabilityEngine` behind application-layer ports (`CalendarProvider`, `AvailabilityConfigRepository`), with in-memory adapters bound as Kodein singletons. CQRS: a query for finding slots, commands for seeding busy blocks and setting rules. All time logic uses `java.time`; the engine is exercised mostly through `testApplication` integration tests, with focused unit tests for tricky pure-logic edge cases.

**Tech Stack:** Kotlin 1.9.23, Ktor 2.3.9 (Netty), Kodein DI 7.21.0, kotlinx.serialization, `java.time`, JUnit (`kotlin-test-junit`). Gradle 8.4 via wrapper.

## Global Constraints

- **All Gradle commands run from the `time-matcher/` subdirectory**, not the repo root.
- Source root: `time-matcher/src/main/kotlin/io/vladar107/`. Test root: `time-matcher/src/test/kotlin/io/vladar107/`.
- **No new dependencies.** Use `java.time` and libraries already on the classpath. Adding a dependency requires a separate discussion.
- **DTO time fields are ISO-8601 strings** (`Instant.toString()`, `LocalTime` `"HH:mm"`, ISO-8601 `Duration` like `PT30M`). No custom kotlinx serializers.
- **Stateful in-memory adapters MUST be bound as Kodein `singleton`**, never `provider` (which discards state between requests).
- **Validate at the web boundary** (parse failures, `from < to`, `duration > 0`, valid zone) → respond `400`. Trust internal code below the boundary.
- **Commits:** author as `Vladislav Ramazaev <vladar107@gmail.com>`, no `Co-Authored-By` trailer. Use `git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" -m "..."`.
- Conventional commit prefixes: `feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`.
- Work happens on branch `feat/availability-finder` (already created).
- Do **not** modify the existing `CreatUserCommand` typo or `web/user/dto/User.kt`'s `toDTO` — out of scope.

---

### Task 1: Fix the red build (replace stale ApplicationTest)

The current `ApplicationTest.kt` imports a nonexistent `web.plugins.configureRouting` and asserts a `/` "Hello World!" route, so the project does not compile. Replace it with a real smoke test that loads the actual module and hits an existing route.

**Files:**
- Modify (overwrite): `time-matcher/src/test/kotlin/io/vladar107/ApplicationTest.kt`

**Interfaces:**
- Consumes: existing `io.vladar107.module` (the `Application.module()` extension in `Application.kt`) and the existing `GET /user` route.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Replace the test file with a real smoke test**

```kotlin
package io.vladar107

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun userRouteResponds() = testApplication {
        application { module() }
        val response = client.get("/user")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
```

- [ ] **Step 2: Run the test and the full build to verify green**

Run (from `time-matcher/`): `./gradlew test`
Expected: BUILD SUCCESSFUL, `ApplicationTest > userRouteResponds` PASSED.

- [ ] **Step 3: Commit**

```bash
git add time-matcher/src/test/kotlin/io/vladar107/ApplicationTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "test: replace stale template ApplicationTest with a real smoke test"
```

---

### Task 2: TimeInterval value object + interval algebra

The core building block: a half-open `[start, end)` interval of `Instant`, plus list operations to merge overlapping intervals and subtract blocked intervals from free ones. These are the trickiest pure-logic functions, so they get focused unit tests.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/availability/TimeInterval.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/availability/BusyInterval.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/domain/availability/TimeIntervalTest.kt`

**Interfaces:**
- Produces:
  - `data class TimeInterval(val start: Instant, val end: Instant)`; `val duration: Duration`; `fun overlaps(other: TimeInterval): Boolean`
  - `fun List<TimeInterval>.merged(): List<TimeInterval>` — sorted, overlapping/touching intervals coalesced
  - `fun List<TimeInterval>.subtract(blocked: List<TimeInterval>): List<TimeInterval>` — each interval minus all blocked intervals
  - `data class BusyInterval(val interval: TimeInterval, val calendarId: String)`

- [ ] **Step 1: Write the failing tests**

`TimeIntervalTest.kt`:

```kotlin
package io.vladar107.domain.availability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeIntervalTest {
    private fun t(s: String): Instant = Instant.parse(s)
    private fun interval(a: String, b: String) = TimeInterval(t(a), t(b))

    @Test
    fun mergesOverlappingAndTouchingIntervals() {
        val merged = listOf(
            interval("2030-01-01T09:00:00Z", "2030-01-01T10:00:00Z"),
            interval("2030-01-01T09:30:00Z", "2030-01-01T10:30:00Z"), // overlaps
            interval("2030-01-01T10:30:00Z", "2030-01-01T11:00:00Z"), // touches
            interval("2030-01-01T12:00:00Z", "2030-01-01T13:00:00Z"), // separate
        ).merged()

        assertEquals(
            listOf(
                interval("2030-01-01T09:00:00Z", "2030-01-01T11:00:00Z"),
                interval("2030-01-01T12:00:00Z", "2030-01-01T13:00:00Z"),
            ),
            merged,
        )
    }

    @Test
    fun subtractSplitsAnIntervalAroundABlock() {
        val free = listOf(interval("2030-01-01T09:00:00Z", "2030-01-01T17:00:00Z"))
        val blocked = listOf(interval("2030-01-01T12:00:00Z", "2030-01-01T13:00:00Z"))

        assertEquals(
            listOf(
                interval("2030-01-01T09:00:00Z", "2030-01-01T12:00:00Z"),
                interval("2030-01-01T13:00:00Z", "2030-01-01T17:00:00Z"),
            ),
            free.subtract(blocked),
        )
    }

    @Test
    fun subtractRemovesFullyCoveredInterval() {
        val free = listOf(interval("2030-01-01T12:00:00Z", "2030-01-01T13:00:00Z"))
        val blocked = listOf(interval("2030-01-01T11:00:00Z", "2030-01-01T14:00:00Z"))
        assertEquals(emptyList(), free.subtract(blocked))
    }

    @Test
    fun subtractWithNoOverlapKeepsIntervalWhole() {
        val free = listOf(interval("2030-01-01T09:00:00Z", "2030-01-01T10:00:00Z"))
        val blocked = listOf(interval("2030-01-01T11:00:00Z", "2030-01-01T12:00:00Z"))
        assertEquals(free, free.subtract(blocked))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.vladar107.domain.availability.TimeIntervalTest"`
Expected: FAIL — compilation error, `TimeInterval` unresolved.

- [ ] **Step 3: Implement `TimeInterval.kt`**

```kotlin
package io.vladar107.domain.availability

import java.time.Duration
import java.time.Instant

/** Half-open interval [start, end) on the absolute timeline. */
data class TimeInterval(val start: Instant, val end: Instant) {
    init { require(start.isBefore(end)) { "start must be before end: $start..$end" } }

    val duration: Duration get() = Duration.between(start, end)

    fun overlaps(other: TimeInterval): Boolean =
        start.isBefore(other.end) && other.start.isBefore(end)

    /** This interval with [block] removed; 0, 1, or 2 remaining pieces. */
    internal fun minus(block: TimeInterval): List<TimeInterval> {
        if (!overlaps(block)) return listOf(this)
        val pieces = mutableListOf<TimeInterval>()
        if (start.isBefore(block.start)) pieces += TimeInterval(start, minOf(end, block.start))
        if (block.end.isBefore(end)) pieces += TimeInterval(maxOf(start, block.end), end)
        return pieces
    }
}

/** Coalesce overlapping or touching intervals into a minimal, sorted list. */
fun List<TimeInterval>.merged(): List<TimeInterval> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.start }
    val result = mutableListOf(sorted.first())
    for (current in sorted.drop(1)) {
        val last = result.last()
        if (!current.start.isAfter(last.end)) {
            if (current.end.isAfter(last.end)) {
                result[result.lastIndex] = TimeInterval(last.start, current.end)
            }
        } else {
            result += current
        }
    }
    return result
}

/** Subtract every blocked interval from every interval in the receiver. */
fun List<TimeInterval>.subtract(blocked: List<TimeInterval>): List<TimeInterval> {
    val merged = blocked.merged()
    return flatMap { interval ->
        merged.fold(listOf(interval)) { pieces, block ->
            pieces.flatMap { it.minus(block) }
        }
    }
}
```

- [ ] **Step 4: Implement `BusyInterval.kt`**

```kotlin
package io.vladar107.domain.availability

/** A busy block sourced from a specific calendar. */
data class BusyInterval(val interval: TimeInterval, val calendarId: String)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.vladar107.domain.availability.TimeIntervalTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/domain/availability/TimeInterval.kt \
        time-matcher/src/main/kotlin/io/vladar107/domain/availability/BusyInterval.kt \
        time-matcher/src/test/kotlin/io/vladar107/domain/availability/TimeIntervalTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add TimeInterval value object and interval algebra"
```

---

### Task 3: Availability configuration types

The rules that define when slots are possible: per-weekday working hours, date-specific overrides, and the engine knobs (granularity, buffers, minimum notice). `rangesFor(date)` resolves an override if present, else the weekday hours.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/availability/AvailabilityRules.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/domain/availability/AvailabilityRulesTest.kt`

**Interfaces:**
- Produces:
  - `data class LocalTimeRange(val start: LocalTime, val end: LocalTime)`
  - `data class WeeklyAvailability(val byDay: Map<DayOfWeek, List<LocalTimeRange>>)`; `fun rangesFor(day: DayOfWeek): List<LocalTimeRange>`
  - `data class DateOverride(val date: LocalDate, val ranges: List<LocalTimeRange>)` (empty `ranges` = unavailable that day)
  - `data class AvailabilityRules(zone: ZoneId, weekly: WeeklyAvailability, overrides: List<DateOverride> = emptyList(), granularity: Duration, bufferBefore: Duration = Duration.ZERO, bufferAfter: Duration = Duration.ZERO, minimumNotice: Duration = Duration.ZERO)`; `fun rangesFor(date: LocalDate): List<LocalTimeRange>`

- [ ] **Step 1: Write the failing tests**

`AvailabilityRulesTest.kt`:

```kotlin
package io.vladar107.domain.availability

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class AvailabilityRulesTest {
    private fun range(a: String, b: String) = LocalTimeRange(LocalTime.parse(a), LocalTime.parse(b))

    private fun rules(overrides: List<DateOverride> = emptyList()) = AvailabilityRules(
        zone = ZoneId.of("UTC"),
        weekly = WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(range("09:00", "17:00")))),
        overrides = overrides,
        granularity = Duration.ofMinutes(30),
    )

    @Test
    fun usesWeekdayHoursWhenNoOverride() {
        // 2030-01-07 is a Monday
        assertEquals(listOf(range("09:00", "17:00")), rules().rangesFor(LocalDate.parse("2030-01-07")))
    }

    @Test
    fun emptyForDayWithNoWeekdayHours() {
        // 2030-01-12 is a Saturday (not configured)
        assertEquals(emptyList(), rules().rangesFor(LocalDate.parse("2030-01-12")))
    }

    @Test
    fun overrideReplacesWeekdayHours() {
        val date = LocalDate.parse("2030-01-07")
        val withOverride = rules(listOf(DateOverride(date, listOf(range("10:00", "12:00")))))
        assertEquals(listOf(range("10:00", "12:00")), withOverride.rangesFor(date))
    }

    @Test
    fun overrideWithEmptyRangesMarksDayUnavailable() {
        val date = LocalDate.parse("2030-01-07")
        val dayOff = rules(listOf(DateOverride(date, emptyList())))
        assertEquals(emptyList(), dayOff.rangesFor(date))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.vladar107.domain.availability.AvailabilityRulesTest"`
Expected: FAIL — `LocalTimeRange`/`AvailabilityRules` unresolved.

- [ ] **Step 3: Implement `AvailabilityRules.kt`**

```kotlin
package io.vladar107.domain.availability

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Half-open local-time range [start, end) within a single day. */
data class LocalTimeRange(val start: LocalTime, val end: LocalTime) {
    init { require(start.isBefore(end)) { "start must be before end: $start..$end" } }
}

/** Recurring working hours keyed by day of week. Multiple ranges per day model breaks (e.g. lunch). */
data class WeeklyAvailability(val byDay: Map<DayOfWeek, List<LocalTimeRange>>) {
    fun rangesFor(day: DayOfWeek): List<LocalTimeRange> = byDay[day] ?: emptyList()
}

/** Exception for a specific date. Empty [ranges] means the day is unavailable. */
data class DateOverride(val date: LocalDate, val ranges: List<LocalTimeRange>)

data class AvailabilityRules(
    val zone: ZoneId,
    val weekly: WeeklyAvailability,
    val overrides: List<DateOverride> = emptyList(),
    val granularity: Duration,
    val bufferBefore: Duration = Duration.ZERO,
    val bufferAfter: Duration = Duration.ZERO,
    val minimumNotice: Duration = Duration.ZERO,
) {
    /** Override for the date if present, otherwise the weekday hours. */
    fun rangesFor(date: LocalDate): List<LocalTimeRange> =
        overrides.firstOrNull { it.date == date }?.ranges ?: weekly.rangesFor(date.dayOfWeek)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.vladar107.domain.availability.AvailabilityRulesTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/domain/availability/AvailabilityRules.kt \
        time-matcher/src/test/kotlin/io/vladar107/domain/availability/AvailabilityRulesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add availability configuration types"
```

---

### Task 4: AvailabilityEngine — core slotting

The pure heart. Projects each day's working ranges into the user's zone, subtracts busy intervals, and emits clean grid-aligned slots that fully fit. This task implements everything EXCEPT buffers and minimum notice (added in Task 5); pass `bufferBefore/After = 0` and `minimumNotice = 0` here.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/availability/AvailabilityEngine.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/domain/availability/AvailabilityEngineTest.kt`

**Interfaces:**
- Consumes: `TimeInterval`, `merged()`, `subtract()` (Task 2); `BusyInterval` (Task 2); `AvailabilityRules`, `LocalTimeRange`, `WeeklyAvailability`, `DateOverride` (Task 3).
- Produces:
  - `data class SlotSearch(val from: Instant, val to: Instant, val duration: Duration)`
  - `class AvailabilityEngine { fun findSlots(rules: AvailabilityRules, busy: List<BusyInterval>, search: SlotSearch, now: Instant): List<TimeInterval> }`

- [ ] **Step 1: Write the failing tests**

`AvailabilityEngineTest.kt`:

```kotlin
package io.vladar107.domain.availability

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityEngineTest {
    private val engine = AvailabilityEngine()
    private fun t(s: String): Instant = Instant.parse(s)
    private fun range(a: String, b: String) = LocalTimeRange(LocalTime.parse(a), LocalTime.parse(b))

    private fun rules(
        zone: String = "UTC",
        granularityMinutes: Long = 30,
        monday: List<LocalTimeRange> = listOf(range("09:00", "17:00")),
        overrides: List<DateOverride> = emptyList(),
    ) = AvailabilityRules(
        zone = ZoneId.of(zone),
        weekly = WeeklyAvailability(mapOf(DayOfWeek.MONDAY to monday)),
        overrides = overrides,
        granularity = Duration.ofMinutes(granularityMinutes),
    )

    // Monday 2030-01-07, whole-day window
    private fun mondaySearch(durationMinutes: Long) = SlotSearch(
        from = t("2030-01-07T00:00:00Z"),
        to = t("2030-01-07T23:59:59Z"),
        duration = Duration.ofMinutes(durationMinutes),
    )

    private val longAgo = t("2020-01-01T00:00:00Z")

    @Test
    fun emptyDayYieldsNoSlots() {
        // Tuesday window with only Monday configured
        val search = SlotSearch(t("2030-01-08T00:00:00Z"), t("2030-01-08T23:59:59Z"), Duration.ofMinutes(60))
        assertEquals(emptyList(), engine.findSlots(rules(), emptyList(), search, longAgo))
    }

    @Test
    fun fullWorkingDaySlicedIntoSlots() {
        // 09:00-17:00, 60-min meetings, 30-min grid, no busy -> 09:00,09:30,...,16:00
        val slots = engine.findSlots(rules(), emptyList(), mondaySearch(60), longAgo)
        assertEquals(t("2030-01-07T09:00:00Z"), slots.first().start)
        assertEquals(t("2030-01-07T16:00:00Z"), slots.last().start)
        assertEquals(15, slots.size)
    }

    @Test
    fun slotsAlignToCleanGridAfterABusyBlock() {
        // busy 10:00-10:20; with 30-min grid the next slot must be 10:30, not 10:20
        val busy = listOf(BusyInterval(TimeInterval(t("2030-01-07T10:00:00Z"), t("2030-01-07T10:20:00Z")), "work"))
        val slots = engine.findSlots(rules(), busy, mondaySearch(60), longAgo)
        // 09:00 fits (09:00-10:00). No 09:30 (would hit busy). Next is 10:30.
        assertEquals(t("2030-01-07T09:00:00Z"), slots[0].start)
        assertEquals(t("2030-01-07T10:30:00Z"), slots[1].start)
        assertTrue(slots.none { it.start == t("2030-01-07T10:20:00Z") })
    }

    @Test
    fun slotThatDoesNotFullyFitIsExcluded() {
        // Single 09:00-09:59 window via override; a 60-min meeting cannot fit.
        val tightDay = rules(overrides = listOf(DateOverride(java.time.LocalDate.parse("2030-01-07"), listOf(range("09:00", "09:59")))))
        assertEquals(emptyList(), engine.findSlots(tightDay, emptyList(), mondaySearch(60), longAgo))
    }

    @Test
    fun projectsWorkingHoursIntoConfiguredZone() {
        // 09:00 in Europe/Paris (summer, UTC+2) == 07:00Z. Use a July Monday: 2030-07-01.
        val search = SlotSearch(t("2030-07-01T00:00:00Z"), t("2030-07-01T23:59:59Z"), Duration.ofMinutes(60))
        val parisRules = AvailabilityRules(
            zone = ZoneId.of("Europe/Paris"),
            weekly = WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(range("09:00", "17:00")))),
            granularity = Duration.ofMinutes(30),
        )
        val slots = engine.findSlots(parisRules, emptyList(), search, longAgo)
        assertEquals(t("2030-07-01T07:00:00Z"), slots.first().start) // 09:00 Paris == 07:00Z
    }

    @Test
    fun handlesSpringForwardDstDay() {
        // Europe/Paris spring-forward is 2030-03-31 (02:00->03:00). A Sunday.
        // Configure SUNDAY 00:00-06:00; the missing hour means 5 real hours, so 5 one-hour slots.
        val date = java.time.LocalDate.parse("2030-03-31")
        val dstRules = AvailabilityRules(
            zone = ZoneId.of("Europe/Paris"),
            weekly = WeeklyAvailability(mapOf(DayOfWeek.SUNDAY to listOf(range("00:00", "06:00")))),
            granularity = Duration.ofMinutes(60),
        )
        val search = SlotSearch(
            from = date.atStartOfDay(ZoneId.of("Europe/Paris")).toInstant(),
            to = date.atTime(LocalTime.parse("06:00")).atZone(ZoneId.of("Europe/Paris")).toInstant(),
            duration = Duration.ofMinutes(60),
        )
        val slots = engine.findSlots(dstRules, emptyList(), search, longAgo)
        assertEquals(5, slots.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.vladar107.domain.availability.AvailabilityEngineTest"`
Expected: FAIL — `AvailabilityEngine`/`SlotSearch` unresolved.

- [ ] **Step 3: Implement `AvailabilityEngine.kt`**

```kotlin
package io.vladar107.domain.availability

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

data class SlotSearch(val from: Instant, val to: Instant, val duration: Duration)

class AvailabilityEngine {

    fun findSlots(
        rules: AvailabilityRules,
        busy: List<BusyInterval>,
        search: SlotSearch,
        now: Instant,
    ): List<TimeInterval> {
        val effectiveStart = maxOf(search.from, now.plus(rules.minimumNotice))
        if (!effectiveStart.isBefore(search.to)) return emptyList()

        // Expand busy by buffers, then merge into a minimal blocked set.
        val blocked = busy
            .map {
                TimeInterval(
                    it.interval.start.minus(rules.bufferBefore),
                    it.interval.end.plus(rules.bufferAfter),
                )
            }
            .merged()

        val startDate = effectiveStart.atZone(rules.zone).toLocalDate()
        val endDate = search.to.atZone(rules.zone).toLocalDate()

        val slots = mutableListOf<TimeInterval>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            val ranges = rules.rangesFor(date)
            if (ranges.isNotEmpty()) {
                // Grid anchor: the day's earliest working-range start, as an Instant.
                val anchorInstant = ZonedDateTime.of(date, ranges.minOf { it.start }, rules.zone).toInstant()

                val working = ranges.mapNotNull { r ->
                    val s = maxOf(ZonedDateTime.of(date, r.start, rules.zone).toInstant(), effectiveStart)
                    val e = minOf(ZonedDateTime.of(date, r.end, rules.zone).toInstant(), search.to)
                    if (s.isBefore(e)) TimeInterval(s, e) else null
                }

                for (free in working.subtract(blocked)) {
                    slots += gridSlots(anchorInstant, free, rules.granularity, search.duration)
                }
            }
            date = date.plusDays(1)
        }
        return slots.sortedBy { it.start }
    }

    private fun gridSlots(
        anchor: Instant,
        free: TimeInterval,
        granularity: Duration,
        duration: Duration,
    ): List<TimeInterval> {
        val granMillis = granularity.toMillis()
        require(granMillis > 0) { "granularity must be positive" }
        val deltaMillis = Duration.between(anchor, free.start).toMillis()
        val k = if (deltaMillis <= 0) 0L else (deltaMillis + granMillis - 1) / granMillis
        var slotStart = anchor.plusMillis(k * granMillis)
        val out = mutableListOf<TimeInterval>()
        while (!slotStart.plus(duration).isAfter(free.end)) {
            out += TimeInterval(slotStart, slotStart.plus(duration))
            slotStart = slotStart.plusMillis(granMillis)
        }
        return out
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.vladar107.domain.availability.AvailabilityEngineTest"`
Expected: PASS (6 tests). If the DST slot count differs, recompute by hand against `java.time` and adjust the assertion — the engine delegates DST to `java.time` by design.

- [ ] **Step 5: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/domain/availability/AvailabilityEngine.kt \
        time-matcher/src/test/kotlin/io/vladar107/domain/availability/AvailabilityEngineTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add availability engine core slotting"
```

---

### Task 5: AvailabilityEngine — buffers and minimum notice

Add the two remaining rules. The engine code from Task 4 already reads `bufferBefore/After` and `minimumNotice`, so this task is primarily adding tests that prove those paths; only adjust the implementation if a test fails.

**Files:**
- Modify (if needed): `time-matcher/src/main/kotlin/io/vladar107/domain/availability/AvailabilityEngine.kt`
- Modify: `time-matcher/src/test/kotlin/io/vladar107/domain/availability/AvailabilityEngineTest.kt` (add tests)

**Interfaces:**
- Consumes/Produces: same `AvailabilityEngine.findSlots` signature as Task 4 (no API change).

- [ ] **Step 1: Add failing tests to `AvailabilityEngineTest.kt`**

Append these methods inside the existing `AvailabilityEngineTest` class:

```kotlin
    @Test
    fun buffersPadBusyBlocksAndMergeAdjacent() {
        // busy 10:00-11:00 with 15-min buffers blocks 09:45-11:15.
        val buffered = rules().copy(bufferBefore = Duration.ofMinutes(15), bufferAfter = Duration.ofMinutes(15))
        val busy = listOf(BusyInterval(TimeInterval(t("2030-01-07T10:00:00Z"), t("2030-01-07T11:00:00Z")), "work"))
        val slots = engine.findSlots(buffered, busy, mondaySearch(60), longAgo)
        // No slot may overlap the buffered block 09:45-11:15.
        val blocked = TimeInterval(t("2030-01-07T09:45:00Z"), t("2030-01-07T11:15:00Z"))
        assertTrue(slots.none { it.overlaps(blocked) })
        // First slot after the block is 11:30 (grid-aligned).
        assertTrue(slots.any { it.start == t("2030-01-07T11:30:00Z") })
    }

    @Test
    fun minimumNoticeClipsTheWindowStart() {
        // "now" is 2030-01-07T09:10Z with 2h notice -> earliest slot start >= 11:10 -> grid -> 11:30.
        val noticed = rules().copy(minimumNotice = Duration.ofHours(2))
        val now = t("2030-01-07T09:10:00Z")
        val slots = engine.findSlots(noticed, emptyList(), mondaySearch(60), now)
        assertTrue(slots.all { !it.start.isBefore(t("2030-01-07T11:10:00Z")) })
        assertEquals(t("2030-01-07T11:30:00Z"), slots.first().start)
    }
```

- [ ] **Step 2: Run tests to verify the new ones pass (or fail meaningfully)**

Run: `./gradlew test --tests "io.vladar107.domain.availability.AvailabilityEngineTest"`
Expected: PASS for all (8 total). The Task 4 implementation already handles buffers/notice; if either new test fails, fix the corresponding branch in `findSlots` (buffer expansion in the `blocked` mapping; `effectiveStart` for notice) and re-run.

- [ ] **Step 3: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/domain/availability/AvailabilityEngine.kt \
        time-matcher/src/test/kotlin/io/vladar107/domain/availability/AvailabilityEngineTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: support buffers and minimum notice in availability engine"
```

---

### Task 6: Application ports + find-slots query

Define the ports the use cases depend on, plus the read-side query and its handler. The handler is tested against in-line fakes and a fixed `Clock`.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/availability/CalendarProvider.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/availability/CalendarBusyWriter.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/availability/AvailabilityConfigRepository.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/availability/FindAvailableSlotsQueryHandler.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/application/availability/FindAvailableSlotsQueryHandlerTest.kt`

**Interfaces:**
- Consumes: `AvailabilityEngine`, `SlotSearch`, `TimeInterval`, `BusyInterval`, `AvailabilityRules` (domain); existing `io.vladar107.infrastructure.Query` and `QueryHandler`.
- Produces:
  - `interface CalendarProvider { suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> }`
  - `interface CalendarBusyWriter { suspend fun addBusy(calendarId: String, interval: TimeInterval) }`
  - `interface AvailabilityConfigRepository { suspend fun load(): AvailabilityRules; suspend fun save(rules: AvailabilityRules) }`
  - `data class FindAvailableSlotsQuery(val from: Instant, val to: Instant, val duration: Duration) : Query<List<TimeInterval>>`
  - `class FindAvailableSlotsQueryHandler(calendarProvider, configRepository, clock, engine = AvailabilityEngine()) : QueryHandler<List<TimeInterval>, FindAvailableSlotsQuery>`

- [ ] **Step 1: Write the failing test**

`FindAvailableSlotsQueryHandlerTest.kt`:

```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.DayHoursFixtures.mondayNineToFive
import io.vladar107.domain.availability.TimeInterval
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindAvailableSlotsQueryHandlerTest {
    private fun t(s: String) = Instant.parse(s)

    private val fakeConfig = object : AvailabilityConfigRepository {
        override suspend fun load(): AvailabilityRules = mondayNineToFive(ZoneId.of("UTC"))
        override suspend fun save(rules: AvailabilityRules) = error("not used")
    }

    private fun providerWith(busy: List<BusyInterval>) = object : CalendarProvider {
        override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> =
            busy.filter { it.interval.overlaps(window) }
    }

    @Test
    fun returnsEngineSlotsForTheWindow() = runBlocking {
        val handler = FindAvailableSlotsQueryHandler(
            calendarProvider = providerWith(emptyList()),
            configRepository = fakeConfig,
            clock = Clock.fixed(t("2020-01-01T00:00:00Z"), ZoneId.of("UTC")),
        )
        val slots = handler.handle(
            FindAvailableSlotsQuery(
                from = t("2030-01-07T00:00:00Z"),
                to = t("2030-01-07T23:59:59Z"),
                duration = Duration.ofMinutes(60),
            )
        )
        assertEquals(t("2030-01-07T09:00:00Z"), slots.first().start)
        assertTrue(slots.isNotEmpty())
    }
}
```

- [ ] **Step 2: Add the shared test fixture**

Create `time-matcher/src/test/kotlin/io/vladar107/domain/availability/DayHoursFixtures.kt`:

```kotlin
package io.vladar107.domain.availability

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

object DayHoursFixtures {
    fun mondayNineToFive(zone: ZoneId): AvailabilityRules = AvailabilityRules(
        zone = zone,
        weekly = WeeklyAvailability(
            mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0)))),
        ),
        granularity = Duration.ofMinutes(30),
    )
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "io.vladar107.application.availability.FindAvailableSlotsQueryHandlerTest"`
Expected: FAIL — ports/handler unresolved.

- [ ] **Step 4: Implement the ports**

`CalendarProvider.kt`:

```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.TimeInterval

/** Read port: busy intervals across all connected calendars, within a window. */
interface CalendarProvider {
    suspend fun busyIntervals(window: TimeInterval): List<BusyInterval>
}
```

`CalendarBusyWriter.kt`:

```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.TimeInterval

/** Write port: seed a calendar's busy blocks (Phase 1 demo source). */
interface CalendarBusyWriter {
    suspend fun addBusy(calendarId: String, interval: TimeInterval)
}
```

`AvailabilityConfigRepository.kt`:

```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityRules

interface AvailabilityConfigRepository {
    suspend fun load(): AvailabilityRules
    suspend fun save(rules: AvailabilityRules)
}
```

- [ ] **Step 5: Implement the query + handler**

`FindAvailableSlotsQueryHandler.kt`:

```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityEngine
import io.vladar107.domain.availability.SlotSearch
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class FindAvailableSlotsQuery(
    val from: Instant,
    val to: Instant,
    val duration: Duration,
) : Query<List<TimeInterval>>

class FindAvailableSlotsQueryHandler(
    private val calendarProvider: CalendarProvider,
    private val configRepository: AvailabilityConfigRepository,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : QueryHandler<List<TimeInterval>, FindAvailableSlotsQuery> {

    override suspend fun handle(query: FindAvailableSlotsQuery): List<TimeInterval> {
        val rules = configRepository.load()
        val window = TimeInterval(query.from, query.to)
        val busy = calendarProvider.busyIntervals(window)
        return engine.findSlots(rules, busy, SlotSearch(query.from, query.to, query.duration), clock.instant())
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "io.vladar107.application.availability.FindAvailableSlotsQueryHandlerTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/application/availability/ \
        time-matcher/src/test/kotlin/io/vladar107/application/availability/ \
        time-matcher/src/test/kotlin/io/vladar107/domain/availability/DayHoursFixtures.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add calendar/config ports and find-available-slots query"
```

---

### Task 7: Commands — add busy block, set rules

The write side: seed busy blocks on a calendar and set the availability rules.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/availability/AddBusyBlockCommandHandler.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/availability/SetAvailabilityRulesCommandHandler.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/application/availability/AvailabilityCommandsTest.kt`

**Interfaces:**
- Consumes: `CalendarBusyWriter`, `AvailabilityConfigRepository` (Task 6); `TimeInterval`, `AvailabilityRules` (domain); existing `io.vladar107.infrastructure.Command` and `CommandHandler`.
- Produces:
  - `data class AddBusyBlockCommand(val calendarId: String, val start: Instant, val end: Instant) : Command<Unit>`
  - `class AddBusyBlockCommandHandler(writer: CalendarBusyWriter) : CommandHandler<Unit, AddBusyBlockCommand>`
  - `data class SetAvailabilityRulesCommand(val rules: AvailabilityRules) : Command<Unit>`
  - `class SetAvailabilityRulesCommandHandler(repository: AvailabilityConfigRepository) : CommandHandler<Unit, SetAvailabilityRulesCommand>`

- [ ] **Step 1: Write the failing test**

`AvailabilityCommandsTest.kt`:

```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.domain.availability.DayHoursFixtures.mondayNineToFive
import io.vladar107.domain.availability.TimeInterval
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class AvailabilityCommandsTest {
    private fun t(s: String) = Instant.parse(s)

    @Test
    fun addBusyBlockWritesToTheCalendar() = runBlocking {
        val written = mutableListOf<Pair<String, TimeInterval>>()
        val writer = object : CalendarBusyWriter {
            override suspend fun addBusy(calendarId: String, interval: TimeInterval) {
                written += calendarId to interval
            }
        }
        AddBusyBlockCommandHandler(writer).handle(
            AddBusyBlockCommand("work", t("2030-01-07T10:00:00Z"), t("2030-01-07T11:00:00Z"))
        )
        assertEquals(1, written.size)
        assertEquals("work", written.first().first)
        assertEquals(TimeInterval(t("2030-01-07T10:00:00Z"), t("2030-01-07T11:00:00Z")), written.first().second)
    }

    @Test
    fun setRulesSavesToTheRepository() = runBlocking {
        var saved: AvailabilityRules? = null
        val repo = object : AvailabilityConfigRepository {
            override suspend fun load() = error("not used")
            override suspend fun save(rules: AvailabilityRules) { saved = rules }
        }
        val rules = mondayNineToFive(ZoneId.of("UTC"))
        SetAvailabilityRulesCommandHandler(repo).handle(SetAvailabilityRulesCommand(rules))
        assertEquals(rules, saved)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.vladar107.application.availability.AvailabilityCommandsTest"`
Expected: FAIL — command/handler unresolved.

- [ ] **Step 3: Implement the command handlers**

`AddBusyBlockCommandHandler.kt`:

```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.TimeInterval
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler
import java.time.Instant

data class AddBusyBlockCommand(
    val calendarId: String,
    val start: Instant,
    val end: Instant,
) : Command<Unit>

class AddBusyBlockCommandHandler(
    private val writer: CalendarBusyWriter,
) : CommandHandler<Unit, AddBusyBlockCommand> {
    override suspend fun handle(command: AddBusyBlockCommand) {
        writer.addBusy(command.calendarId, TimeInterval(command.start, command.end))
    }
}
```

`SetAvailabilityRulesCommandHandler.kt`:

```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler

data class SetAvailabilityRulesCommand(val rules: AvailabilityRules) : Command<Unit>

class SetAvailabilityRulesCommandHandler(
    private val repository: AvailabilityConfigRepository,
) : CommandHandler<Unit, SetAvailabilityRulesCommand> {
    override suspend fun handle(command: SetAvailabilityRulesCommand) {
        repository.save(command.rules)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.vladar107.application.availability.AvailabilityCommandsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/application/availability/AddBusyBlockCommandHandler.kt \
        time-matcher/src/main/kotlin/io/vladar107/application/availability/SetAvailabilityRulesCommandHandler.kt \
        time-matcher/src/test/kotlin/io/vladar107/application/availability/AvailabilityCommandsTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add commands to seed busy blocks and set availability rules"
```

---

### Task 8: In-memory adapters

Concrete adapters: an in-memory calendar store (read + write, aggregating across calendars) and an in-memory config repository seeded with a sensible default. The multi-calendar union is the focused unit test here.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/data/repositories/InMemoryCalendarProvider.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/data/repositories/InMemoryAvailabilityConfigRepository.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/data/repositories/InMemoryCalendarProviderTest.kt`

**Interfaces:**
- Consumes: `CalendarProvider`, `CalendarBusyWriter`, `AvailabilityConfigRepository` (Task 6); `TimeInterval`, `BusyInterval`, `AvailabilityRules`, `WeeklyAvailability`, `LocalTimeRange` (domain).
- Produces:
  - `class InMemoryCalendarProvider : CalendarProvider, CalendarBusyWriter`
  - `class InMemoryAvailabilityConfigRepository(initial: AvailabilityRules = default) : AvailabilityConfigRepository`, with `companion object { val default: AvailabilityRules }` — Mon–Fri 09:00–17:00, zone `Europe/Paris`, 30-min granularity.

- [ ] **Step 1: Write the failing test**

`InMemoryCalendarProviderTest.kt`:

```kotlin
package io.vladar107.data.repositories

import io.vladar107.domain.availability.TimeInterval
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryCalendarProviderTest {
    private fun t(s: String) = Instant.parse(s)

    @Test
    fun unionsBusyAcrossCalendarsWithinWindow() = runBlocking {
        val provider = InMemoryCalendarProvider()
        provider.addBusy("work", TimeInterval(t("2030-01-07T10:00:00Z"), t("2030-01-07T11:00:00Z")))
        provider.addBusy("personal", TimeInterval(t("2030-01-07T14:00:00Z"), t("2030-01-07T15:00:00Z")))
        // Outside the query window -> excluded.
        provider.addBusy("work", TimeInterval(t("2030-02-01T10:00:00Z"), t("2030-02-01T11:00:00Z")))

        val window = TimeInterval(t("2030-01-07T00:00:00Z"), t("2030-01-07T23:59:59Z"))
        val busy = provider.busyIntervals(window)

        assertEquals(2, busy.size)
        assertEquals(setOf("work", "personal"), busy.map { it.calendarId }.toSet())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.vladar107.data.repositories.InMemoryCalendarProviderTest"`
Expected: FAIL — `InMemoryCalendarProvider` unresolved.

- [ ] **Step 3: Implement `InMemoryCalendarProvider.kt`**

```kotlin
package io.vladar107.data.repositories

import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.TimeInterval
import java.util.concurrent.ConcurrentHashMap

/** In-memory busy store, aggregating across calendars. Bind as a Kodein singleton. */
class InMemoryCalendarProvider : CalendarProvider, CalendarBusyWriter {
    private val byCalendar = ConcurrentHashMap<String, MutableList<TimeInterval>>()

    override suspend fun addBusy(calendarId: String, interval: TimeInterval) {
        byCalendar.computeIfAbsent(calendarId) { mutableListOf() }.add(interval)
    }

    override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> =
        byCalendar.flatMap { (calendarId, intervals) ->
            intervals.filter { it.overlaps(window) }.map { BusyInterval(it, calendarId) }
        }
}
```

- [ ] **Step 4: Implement `InMemoryAvailabilityConfigRepository.kt`**

```kotlin
package io.vladar107.data.repositories

import io.vladar107.application.availability.AvailabilityConfigRepository
import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/** In-memory availability config seeded with a default. Bind as a Kodein singleton. */
class InMemoryAvailabilityConfigRepository(
    initial: AvailabilityRules = default,
) : AvailabilityConfigRepository {

    @Volatile
    private var rules: AvailabilityRules = initial

    override suspend fun load(): AvailabilityRules = rules

    override suspend fun save(rules: AvailabilityRules) {
        this.rules = rules
    }

    companion object {
        val default: AvailabilityRules = AvailabilityRules(
            zone = ZoneId.of("Europe/Paris"),
            weekly = WeeklyAvailability(
                DayOfWeek.entries
                    .filter { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }
                    .associateWith { listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))) },
            ),
            granularity = Duration.ofMinutes(30),
        )
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "io.vladar107.data.repositories.InMemoryCalendarProviderTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/data/repositories/InMemoryCalendarProvider.kt \
        time-matcher/src/main/kotlin/io/vladar107/data/repositories/InMemoryAvailabilityConfigRepository.kt \
        time-matcher/src/test/kotlin/io/vladar107/data/repositories/InMemoryCalendarProviderTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add in-memory calendar and availability-config adapters"
```

---

### Task 9: Web layer + DI wiring + primary integration test

Wire everything into Ktor: DTOs, the three endpoints, DI bindings (singletons + `Clock` + handlers), and registration in `module()`. The primary happy-path integration test is written first (failing), then made to pass — this is the main verification for the slice.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/web/availability/dto/SlotDto.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/web/availability/dto/BusyBlockRequest.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/web/availability/dto/AvailabilityConfigRequest.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/web/availability/AvailabilityController.kt`
- Modify: `time-matcher/src/main/kotlin/io/vladar107/web/di/ConfigureRepositories.kt`
- Modify: `time-matcher/src/main/kotlin/io/vladar107/web/di/ConfigureQueries.kt`
- Modify: `time-matcher/src/main/kotlin/io/vladar107/web/di/ConfigureCommands.kt`
- Modify: `time-matcher/src/main/kotlin/io/vladar107/web/di/ConfigureExternalServices.kt`
- Modify: `time-matcher/src/main/kotlin/io/vladar107/Application.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/web/availability/AvailabilityRoutesTest.kt`

**Interfaces:**
- Consumes: `CommandProvider`/`QueryProvider` (existing infrastructure); all handlers, ports, adapters, commands, and the query from Tasks 6–8; `TimeInterval` (domain).
- Produces:
  - `@Serializable data class SlotDto(val start: String, val end: String)`
  - `@Serializable data class BusyBlockRequest(val start: String, val end: String)`
  - `@Serializable data class AvailabilityConfigRequest(...)` + `fun toRules(): AvailabilityRules`
  - `fun Application.configureAvailability()`

- [ ] **Step 1: Write the failing integration test**

`AvailabilityRoutesTest.kt`:

```kotlin
package io.vladar107.web.availability

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.vladar107.module
import io.vladar107.web.availability.dto.SlotDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityRoutesTest {

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun findsSlotsAroundBusyBlocksAcrossCalendars() = testApplication {
        application { module() }
        val client = jsonClient()

        // Configure UTC, Monday 09:00-17:00, 30-min grid.
        val config = client.put("/availability/config") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "zone": "UTC",
                  "granularityMinutes": 30,
                  "weekly": { "MONDAY": [ { "start": "09:00", "end": "17:00" } ] },
                  "overrides": []
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.NoContent, config.status)

        // Busy 10:00-11:00 on the work calendar.
        val busy = client.post("/calendars/work/busy") {
            contentType(ContentType.Application.Json)
            setBody("""{ "start": "2030-01-07T10:00:00Z", "end": "2030-01-07T11:00:00Z" }""")
        }
        assertEquals(HttpStatusCode.Created, busy.status)

        // Find 1-hour slots on Monday 2030-01-07.
        val response = client.get("/availability/slots") {
            parameter("from", "2030-01-07T00:00:00Z")
            parameter("to", "2030-01-07T23:59:59Z")
            parameter("duration", "PT1H")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val slots = Json.decodeFromString<List<SlotDto>>(response.bodyAsText())

        assertEquals("2030-01-07T09:00:00Z", slots.first().start)
        assertTrue(slots.none { it.start == "2030-01-07T10:00:00Z" }) // inside busy
        assertEquals(12, slots.size) // 09:00 + 11:00,11:30,...,16:00
    }

    @Test
    fun rejectsInvalidWindow() = testApplication {
        application { module() }
        val response = jsonClient().get("/availability/slots") {
            parameter("from", "2030-01-07T12:00:00Z")
            parameter("to", "2030-01-07T09:00:00Z") // to < from
            parameter("duration", "PT1H")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.vladar107.web.availability.AvailabilityRoutesTest"`
Expected: FAIL — `configureAvailability`/DTOs unresolved (and routes 404 once compiling).

- [ ] **Step 3: Implement the DTOs**

`SlotDto.kt`:

```kotlin
package io.vladar107.web.availability.dto

import kotlinx.serialization.Serializable

@Serializable
data class SlotDto(val start: String, val end: String)
```

`BusyBlockRequest.kt`:

```kotlin
package io.vladar107.web.availability.dto

import kotlinx.serialization.Serializable

@Serializable
data class BusyBlockRequest(val start: String, val end: String)
```

`AvailabilityConfigRequest.kt`:

```kotlin
package io.vladar107.web.availability.dto

import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class LocalTimeRangeDto(val start: String, val end: String)

@Serializable
data class DateOverrideDto(val date: String, val ranges: List<LocalTimeRangeDto>)

@Serializable
data class AvailabilityConfigRequest(
    val zone: String,
    val granularityMinutes: Long,
    val bufferBeforeMinutes: Long = 0,
    val bufferAfterMinutes: Long = 0,
    val minimumNoticeMinutes: Long = 0,
    val weekly: Map<String, List<LocalTimeRangeDto>> = emptyMap(),
    val overrides: List<DateOverrideDto> = emptyList(),
) {
    /** Parse into domain rules. Throws IllegalArgumentException / DateTimeException on bad input. */
    fun toRules(): AvailabilityRules {
        fun range(dto: LocalTimeRangeDto) = LocalTimeRange(LocalTime.parse(dto.start), LocalTime.parse(dto.end))
        return AvailabilityRules(
            zone = ZoneId.of(zone),
            weekly = WeeklyAvailability(
                weekly.entries.associate { (day, ranges) ->
                    DayOfWeek.valueOf(day.uppercase()) to ranges.map(::range)
                },
            ),
            overrides = overrides.map { DateOverride(LocalDate.parse(it.date), it.ranges.map(::range)) },
            granularity = Duration.ofMinutes(granularityMinutes),
            bufferBefore = Duration.ofMinutes(bufferBeforeMinutes),
            bufferAfter = Duration.ofMinutes(bufferAfterMinutes),
            minimumNotice = Duration.ofMinutes(minimumNoticeMinutes),
        )
    }
}
```

- [ ] **Step 4: Implement the controller**

`AvailabilityController.kt`:

```kotlin
package io.vladar107.web.availability

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.vladar107.application.availability.AddBusyBlockCommand
import io.vladar107.application.availability.FindAvailableSlotsQuery
import io.vladar107.application.availability.SetAvailabilityRulesCommand
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.web.availability.dto.AvailabilityConfigRequest
import io.vladar107.web.availability.dto.BusyBlockRequest
import io.vladar107.web.availability.dto.SlotDto
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.time.Duration
import java.time.Instant

fun Application.configureAvailability() {
    val commandProvider by closestDI { this@configureAvailability }.instance<CommandProvider>()
    val queryProvider by closestDI { this@configureAvailability }.instance<QueryProvider>()

    routing {
        get("/availability/slots") {
            val from = call.request.queryParameters["from"]
            val to = call.request.queryParameters["to"]
            val duration = call.request.queryParameters["duration"]
            val query = try {
                val fromInstant = Instant.parse(from)
                val toInstant = Instant.parse(to)
                val dur = Duration.parse(duration)
                require(fromInstant.isBefore(toInstant)) { "from must be before to" }
                require(!dur.isZero && !dur.isNegative) { "duration must be positive" }
                FindAvailableSlotsQuery(fromInstant, toInstant, dur)
            } catch (e: Exception) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid query: ${e.message}")
            }
            val slots = queryProvider.query(query)
            call.respond(slots.map { SlotDto(it.start.toString(), it.end.toString()) })
        }

        put("/availability/config") {
            val rules = try {
                call.receive<AvailabilityConfigRequest>().toRules()
            } catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid config: ${e.message}")
            }
            commandProvider.run(SetAvailabilityRulesCommand(rules))
            call.respond(HttpStatusCode.NoContent)
        }

        post("/calendars/{calendarId}/busy") {
            val calendarId = call.parameters["calendarId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing calendarId")
            val command = try {
                val body = call.receive<BusyBlockRequest>()
                val start = Instant.parse(body.start)
                val end = Instant.parse(body.end)
                require(start.isBefore(end)) { "start must be before end" }
                AddBusyBlockCommand(calendarId, start, end)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid busy block: ${e.message}")
            }
            commandProvider.run(command)
            call.respond(HttpStatusCode.Created)
        }
    }
}
```

- [ ] **Step 5: Wire DI — repositories (singletons!)**

Replace the body of `ConfigureRepositories.kt`:

```kotlin
package io.vladar107.web.di

import io.vladar107.application.availability.AvailabilityConfigRepository
import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.userCreation.UserCreationRepository
import io.vladar107.data.repositories.InMemoryAvailabilityConfigRepository
import io.vladar107.data.repositories.InMemoryCalendarProvider
import io.vladar107.data.repositories.UserRepository
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.provider
import org.kodein.di.singleton

fun DI.MainBuilder.configureRepositories() {
    bind<UserCreationRepository>() with provider { UserRepository() }

    // Stateful in-memory store shared across read (CalendarProvider) and write (CalendarBusyWriter).
    bind<InMemoryCalendarProvider>() with singleton { InMemoryCalendarProvider() }
    bind<CalendarProvider>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<CalendarBusyWriter>() with singleton { instance<InMemoryCalendarProvider>() }

    bind<AvailabilityConfigRepository>() with singleton { InMemoryAvailabilityConfigRepository() }
}
```

Note: this keeps the existing `UserCreationRepository` binding unchanged and only adds the new singleton bindings.

- [ ] **Step 6: Wire DI — clock, queries, commands**

Replace `ConfigureExternalServices.kt`:

```kotlin
package io.vladar107.web.di

import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import java.time.Clock

fun DI.MainBuilder.configureExternalServices() {
    bind<Clock>() with singleton { Clock.systemDefaultZone() }
}
```

Replace `ConfigureQueries.kt`:

```kotlin
package io.vladar107.web.di

import io.vladar107.application.availability.FindAvailableSlotsQuery
import io.vladar107.application.availability.FindAvailableSlotsQueryHandler
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.infrastructure.QueryHandler
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.provider

fun DI.MainBuilder.configureQueries() {
    bind<QueryHandler<List<TimeInterval>, FindAvailableSlotsQuery>>() with provider {
        FindAvailableSlotsQueryHandler(instance(), instance(), instance())
    }
}
```

Replace `ConfigureCommands.kt` (keep the existing user binding, add the two new ones):

```kotlin
package io.vladar107.web.di

import io.vladar107.application.availability.AddBusyBlockCommand
import io.vladar107.application.availability.AddBusyBlockCommandHandler
import io.vladar107.application.availability.SetAvailabilityRulesCommand
import io.vladar107.application.availability.SetAvailabilityRulesCommandHandler
import io.vladar107.application.userCreation.CreatUserCommand
import io.vladar107.application.userCreation.CreateUserCommandHandler
import io.vladar107.infrastructure.CommandHandler
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.provider

fun DI.MainBuilder.configureCommands() {
    bind<CommandHandler<Unit, CreatUserCommand>>() with provider {
        CreateUserCommandHandler(instance())
    }
    bind<CommandHandler<Unit, AddBusyBlockCommand>>() with provider {
        AddBusyBlockCommandHandler(instance())
    }
    bind<CommandHandler<Unit, SetAvailabilityRulesCommand>>() with provider {
        SetAvailabilityRulesCommandHandler(instance())
    }
}
```

- [ ] **Step 7: Register the controller in `Application.kt`**

Add the import and the call in `module()`:

```kotlin
import io.vladar107.web.availability.configureAvailability
```

```kotlin
fun Application.module() {
    configureDi()
    configureOpenAPI()
    configureMonitoring()
    configureSerialization()
    configureUser()
    configureAvailability()
}
```

- [ ] **Step 8: Run the integration test to verify it passes**

Run: `./gradlew test --tests "io.vladar107.web.availability.AvailabilityRoutesTest"`
Expected: PASS (2 tests). If the slot count assertion fails, recompute by hand: free = 09:00–10:00 and 11:00–17:00; 1-hour slots on a 30-min grid → 09:00 + (11:00,11:30,…,16:00) = 12.

- [ ] **Step 9: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/web/availability/ \
        time-matcher/src/main/kotlin/io/vladar107/web/di/ \
        time-matcher/src/main/kotlin/io/vladar107/Application.kt \
        time-matcher/src/test/kotlin/io/vladar107/web/availability/AvailabilityRoutesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: expose availability finder over HTTP with DI wiring"
```

---

### Task 10: Extra integration scenarios + documentation

Add a couple of realistic integration scenarios, then record the architecture decision and update the diagrams in the project's existing style.

**Files:**
- Modify: `time-matcher/src/test/kotlin/io/vladar107/web/availability/AvailabilityRoutesTest.kt` (add scenarios)
- Create: `documentation/adr/20260629-availability-engine-and-calendar-ports.md`
- Modify: `documentation/diagrams/Container.md` (add a Phase-1 note)

**Interfaces:**
- Consumes: the running app from Task 9. No new production symbols.

- [ ] **Step 1: Add integration scenarios**

Append to `AvailabilityRoutesTest` (reuse the `jsonClient()` helper):

```kotlin
    @Test
    fun closedDayHasNoSlots() = testApplication {
        application { module() }
        val client = jsonClient()
        client.put("/availability/config") {
            contentType(ContentType.Application.Json)
            setBody("""{ "zone": "UTC", "granularityMinutes": 30, "weekly": { "MONDAY": [ { "start": "09:00", "end": "17:00" } ] }, "overrides": [] }""")
        }
        // 2030-01-08 is a Tuesday (not configured).
        val response = client.get("/availability/slots") {
            parameter("from", "2030-01-08T00:00:00Z")
            parameter("to", "2030-01-08T23:59:59Z")
            parameter("duration", "PT1H")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val slots = Json.decodeFromString<List<SlotDto>>(response.bodyAsText())
        assertTrue(slots.isEmpty())
    }

    @Test
    fun busyBlockSplitsTheWorkingDay() = testApplication {
        application { module() }
        val client = jsonClient()
        client.put("/availability/config") {
            contentType(ContentType.Application.Json)
            setBody("""{ "zone": "UTC", "granularityMinutes": 60, "weekly": { "MONDAY": [ { "start": "09:00", "end": "12:00" } ] }, "overrides": [] }""")
        }
        client.post("/calendars/work/busy") {
            contentType(ContentType.Application.Json)
            setBody("""{ "start": "2030-01-07T10:00:00Z", "end": "2030-01-07T11:00:00Z" }""")
        }
        val response = client.get("/availability/slots") {
            parameter("from", "2030-01-07T00:00:00Z")
            parameter("to", "2030-01-07T23:59:59Z")
            parameter("duration", "PT1H")
        }
        val slots = Json.decodeFromString<List<SlotDto>>(response.bodyAsText())
        // 09:00-12:00 minus 10:00-11:00 -> only 09:00 and 11:00 fit a 1h slot.
        assertEquals(listOf("2030-01-07T09:00:00Z", "2030-01-07T11:00:00Z"), slots.map { it.start })
    }
```

- [ ] **Step 2: Run the new scenarios**

Run: `./gradlew test --tests "io.vladar107.web.availability.AvailabilityRoutesTest"`
Expected: PASS (4 tests).

- [ ] **Step 3: Write the ADR**

Create `documentation/adr/20260629-availability-engine-and-calendar-ports.md`:

```markdown
# Availability as a pure engine behind calendar-source ports

- Status: accepted
- Deciders: vladar107
- Date: 2026-06-29
- Tags: architecture, availability

## Context and Problem Statement

Phase 1 of Time Matcher must find open meeting slots across multiple calendars. We need calendar data and persistence, but want to build and test the core logic without committing to OAuth or a database yet.

## Decision Outcome

The availability logic lives in a pure domain `AvailabilityEngine` (no I/O). Calendar busy times and availability configuration are reached through application-layer ports (`CalendarProvider`, `CalendarBusyWriter`, `AvailabilityConfigRepository`). Phase 1 ships in-memory adapters; a Google Calendar adapter and a database adapter slot in later behind the same ports with no engine changes.

Consequences:
- Time handling uses `java.time` only — no new dependencies.
- Stateful in-memory adapters are bound as Kodein `singleton` (not `provider`), or per-request state would be lost.
- DST and multi-range days are delegated to `java.time` defaults, which is acceptable for Phase 1.
- `max-meetings-per-day` is deferred to Phase 2, where bookings exist to count.
```

- [ ] **Step 4: Update the container diagram note**

In `documentation/diagrams/Container.md`, add this line directly under the first `C4 Container Diagram` title line:

```markdown
> Phase 1 (implemented): in-app availability finder — `GET /availability/slots` over in-memory calendars. External calendar sync, persistence, booking, and bots are later phases.
```

- [ ] **Step 5: Final full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add time-matcher/src/test/kotlin/io/vladar107/web/availability/AvailabilityRoutesTest.kt \
        documentation/adr/20260629-availability-engine-and-calendar-ports.md \
        documentation/diagrams/Container.md
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "test: add availability scenarios; docs: ADR and diagram for Phase 1"
```

---

## Self-Review

**Spec coverage:**
- Core baseline (aggregate busy, weekly hours, zone, duration, window, granularity) → Tasks 2–4, 8.
- Buffers, minimum notice → Task 5. Date overrides → Tasks 3–4.
- `CalendarProvider` port + in-memory adapter, multi-calendar union → Tasks 6, 8.
- CQRS query + commands, fills empty `configureQueries` → Tasks 6, 7, 9.
- Three endpoints (`GET /availability/slots`, `PUT /availability/config`, `POST /calendars/{id}/busy`) → Task 9.
- Kodein `singleton` for stateful adapters → Task 9 step 5. Injected `Clock` → Task 9 step 6.
- ISO-8601 string DTOs, boundary validation → Task 9 (DTOs + controller). 400 on bad input → Task 9 tests.
- Replace broken `ApplicationTest` → Task 1. ADR + diagram update → Task 10.
- Integration-weighted tests, edge-case unit tests → engine unit tests (Tasks 4–5), integration suite (Tasks 9–10).
- Out of scope (Google/DB/booking/max-per-day) → not implemented; recorded in ADR.

**Placeholder scan:** No TBD/TODO; every code step contains complete code and exact commands.

**Type consistency:** `findSlots(rules, busy, search, now)` identical across Tasks 4–6. `busyIntervals(window)`, `addBusy(calendarId, interval)`, `load()/save(rules)` consistent across ports (6), adapters (8), and DI (9). `FindAvailableSlotsQuery(from,to,duration)` consistent across 6 and 9. DTO field names (`start`, `end`, `zone`, `granularityMinutes`, `weekly`, `overrides`) consistent across DTO definitions and the integration-test JSON bodies.
