# Phase 2 Slice 2a — EventTypes + Booking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A host defines EventTypes (persisted in H2); anyone with an EventType's link sees its open slots and books one; the booking is written to the calendar (in-memory adapter) and immediately stops being offered. Configuration persists across restarts; bookings live in the calendar.

**Architecture:** Reuse the Phase-1 pure `AvailabilityEngine` unchanged — assemble its `AvailabilityRules` input from a host-global `Settings` plus a per-`EventType` duration/buffers. Configuration (Settings, EventTypes, connected calendars) persists in H2 via Exposed + Flyway. Bookings are written through a new `CalendarWriter` port to the in-memory calendar (real Google in slice 2b). Booking validation + write run under a lock to prevent double-booking.

**Tech Stack:** Kotlin 2.4, Ktor 3.5.1, Kodein 7.32, H2 (file), Flyway, Exposed (Kotlin SQL DSL), `java.time`, JDK 25, Gradle 9.5.

## Global Constraints

- **All Gradle commands run from `time-matcher/`.** JDK 25 is keg-only: prefix every Gradle call with `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` and target the project dir, e.g.
  `JAVA_HOME=… /Users/vramazaev/src/time-matcher/time-matcher/gradlew -p /Users/vramazaev/src/time-matcher/time-matcher <task>`.
- Source root: `time-matcher/src/main/kotlin/io/vladar107/`. Test root: `time-matcher/src/test/kotlin/io/vladar107/`. Resources: `time-matcher/src/main/resources/`.
- **New dependencies (this slice):** H2 (`com.h2database:h2`), Flyway (`org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-h2`), Exposed (`org.jetbrains.exposed:exposed-core` + `-jdbc` + the java-time module). Versions pinned in `gradle.properties`. **Their exact coordinates/versions MUST be verified empirically against Gradle 9.5 / Kotlin 2.4 / JDK 25 in Task 1** — Exposed 1.0 reorganized its modules/packages/query DSL; if it causes friction, fall back to the latest Exposed 0.x (`exposed-core`/`exposed-jdbc`/`exposed-java-time`). Record the final working set; later tasks' Exposed imports/query spellings follow whatever Task 1 pins.
- **Add new `io.ktor:*` deps without a version** (the `ktor-bom` aligns them). Pin H2/Flyway/Exposed.
- **Persistence is config-only.** Booked events are NEVER stored in the DB; they live in the calendar (the in-memory calendar adapter for this slice).
- **DB URL.** Runtime uses H2 **file mode** via `db.url` in `application.yaml` (config persists across restarts — the user's explicit choice): `jdbc:h2:file:./data/timematcher;DB_CLOSE_DELAY=-1`; add `data/` to `.gitignore`. **Tests override `db.url` to a unique in-memory H2 URL** via `testApplication` config so each test starts on a fresh schema. `Db.init` reconnects Exposed's global `Database`, so tests run sequentially — set `tasks.test { maxParallelForks = 1 }` in `build.gradle.kts`.
- **Build stays green at every task boundary.** A task that changes or deletes a referenced symbol updates all references (DI bindings, controllers) in the same task, so `./gradlew build` compiles and passes after every task. New, not-yet-wired handler classes are fine (they compile unbound); binding + exposing them is the job of their web task.
- **The Phase-1 `AvailabilityEngine` is not modified.** Only the assembly of its `AvailabilityRules` input changes.
- **Stateful adapters are Kodein `singleton`** (DB repos, in-memory calendar, the booking handler which holds a Mutex). Stateless handlers stay `provider`.
- **Commits:** author `Vladislav Ramazaev <vladar107@gmail.com>`, NO co-author trailer:
  `git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" -m "…"`.
- Branch: `feat/eventtypes-booking` (already created). Conventional commit prefixes. Validate at the web boundary → 400.

---

### Task 1: Persistence foundation (deps + H2 schema + Exposed + Flyway init) — DE-RISK GATE

Stand up the config database and prove the H2/Flyway/Exposed stack works on the toolchain before any feature is built on it.

**Files:**
- Modify: `time-matcher/build.gradle.kts` (deps + `tasks.test { maxParallelForks = 1 }`), `time-matcher/gradle.properties` (versions)
- Create: `time-matcher/src/main/resources/db/migration/V1__init.sql`
- Create: `time-matcher/src/main/kotlin/io/vladar107/data/persistence/Database.kt`, `…/data/persistence/Tables.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/data/persistence/DatabaseTest.kt`

**Interfaces:**
- Produces: `object Db { fun init(jdbcUrl: String, user: String = "sa", password: String = ""): Database }` (Flyway migrate then Exposed connect); Exposed tables `SettingsTable`, `WorkingHoursTable`, `DateOverrideTable`, `EventTypeTable`, `ConnectedCalendarTable` (columns match `V1__init.sql`).

- [ ] **Step 1: Add dependencies + sequential tests**

`gradle.properties` (verify/adjust in Step 4):
```
h2_version=2.3.232
flyway_version=11.9.0
exposed_version=1.0.0
```
`build.gradle.kts` — version vals at top alongside the existing ones:
```kotlin
val h2_version: String by project
val flyway_version: String by project
val exposed_version: String by project
```
`dependencies { }`:
```kotlin
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("com.h2database:h2:$h2_version")
    implementation("org.flywaydb:flyway-core:$flyway_version")
    implementation("org.flywaydb:flyway-database-h2:$flyway_version")
```
At the bottom of `build.gradle.kts` (Exposed's global connection requires serial tests):
```kotlin
tasks.test { maxParallelForks = 1 }
```

- [ ] **Step 2: Write `V1__init.sql`**
```sql
CREATE TABLE settings (
    id INT PRIMARY KEY,
    zone VARCHAR(64) NOT NULL,
    granularity_minutes INT NOT NULL,
    minimum_notice_minutes INT NOT NULL
);
CREATE TABLE working_hours (
    id UUID PRIMARY KEY, day_of_week VARCHAR(9) NOT NULL,
    start_time VARCHAR(5) NOT NULL, end_time VARCHAR(5) NOT NULL
);
CREATE TABLE date_override (
    id UUID PRIMARY KEY, override_date VARCHAR(10) NOT NULL,
    start_time VARCHAR(5), end_time VARCHAR(5)
);
CREATE TABLE event_type (
    id UUID PRIMARY KEY, slug VARCHAR(128) NOT NULL UNIQUE, name VARCHAR(256) NOT NULL,
    duration_minutes INT NOT NULL, buffer_before_minutes INT NOT NULL,
    buffer_after_minutes INT NOT NULL, status VARCHAR(16) NOT NULL
);
CREATE TABLE connected_calendar (
    id UUID PRIMARY KEY, name VARCHAR(256) NOT NULL, provider VARCHAR(32) NOT NULL, created_at VARCHAR(64) NOT NULL
);
INSERT INTO settings (id, zone, granularity_minutes, minimum_notice_minutes) VALUES (1, 'Europe/Paris', 30, 0);
INSERT INTO connected_calendar (id, name, provider, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default', 'IN_MEMORY', '2026-06-29T00:00:00Z');
```
Times are stored as `HH:mm`/ISO strings (DB-agnostic; repos parse to `java.time`).

- [ ] **Step 3: Implement `Tables.kt` and `Database.kt`**

`Tables.kt`:
```kotlin
package io.vladar107.data.persistence

import org.jetbrains.exposed.sql.Table

object SettingsTable : Table("settings") {
    val id = integer("id"); val zone = varchar("zone", 64)
    val granularityMinutes = integer("granularity_minutes"); val minimumNoticeMinutes = integer("minimum_notice_minutes")
    override val primaryKey = PrimaryKey(id)
}
object WorkingHoursTable : Table("working_hours") {
    val id = uuid("id"); val dayOfWeek = varchar("day_of_week", 9)
    val startTime = varchar("start_time", 5); val endTime = varchar("end_time", 5)
    override val primaryKey = PrimaryKey(id)
}
object DateOverrideTable : Table("date_override") {
    val id = uuid("id"); val date = varchar("override_date", 10)
    val startTime = varchar("start_time", 5).nullable(); val endTime = varchar("end_time", 5).nullable()
    override val primaryKey = PrimaryKey(id)
}
object EventTypeTable : Table("event_type") {
    val id = uuid("id"); val slug = varchar("slug", 128).uniqueIndex(); val name = varchar("name", 256)
    val durationMinutes = integer("duration_minutes"); val bufferBeforeMinutes = integer("buffer_before_minutes")
    val bufferAfterMinutes = integer("buffer_after_minutes"); val status = varchar("status", 16)
    override val primaryKey = PrimaryKey(id)
}
object ConnectedCalendarTable : Table("connected_calendar") {
    val id = uuid("id"); val name = varchar("name", 256); val provider = varchar("provider", 32); val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}
```
`Database.kt`:
```kotlin
package io.vladar107.data.persistence

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

object Db {
    fun init(jdbcUrl: String, user: String = "sa", password: String = ""): Database {
        Flyway.configure().dataSource(jdbcUrl, user, password).load().migrate()
        return Database.connect(jdbcUrl, driver = "org.h2.Driver", user = user, password = password)
    }
}
```

- [ ] **Step 4: Smoke test + verify the stack (adjust versions/imports if needed)**

`DatabaseTest.kt`:
```kotlin
package io.vladar107.data.persistence

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseTest {
    @Test fun migratesAndSeedsConfig() {
        Db.init("jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1")
        transaction {
            assertEquals(1, SettingsTable.selectAll().count().toInt())
            assertEquals("Europe/Paris", SettingsTable.selectAll().single()[SettingsTable.zone])
            assertEquals(1, ConnectedCalendarTable.selectAll().count().toInt())
        }
    }
}
```
Run: `… gradlew -p … test --tests "io.vladar107.data.persistence.DatabaseTest"` → PASS. **If dependency resolution or compilation fails** (Exposed 1.0 module/package/query-DSL names, Flyway H2 module, H2 version), adjust `gradle.properties` versions and the imports/DSL to the working combination (try latest Exposed 0.x with `exposed-java-time` if 1.0 fights you), re-run until green, and record the final versions + any DSL spelling in the report.

- [ ] **Step 5: Full build + commit**

Run: `… gradlew -p … build` → BUILD SUCCESSFUL.
```bash
git add time-matcher/build.gradle.kts time-matcher/gradle.properties \
        time-matcher/src/main/resources/db/migration/V1__init.sql \
        time-matcher/src/main/kotlin/io/vladar107/data/persistence/ \
        time-matcher/src/test/kotlin/io/vladar107/data/persistence/DatabaseTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add H2 + Flyway + Exposed persistence foundation and config schema"
```

---

### Task 2: Booking domain + repository ports + effective-rules assembly

**Files:**
- Create: `domain/booking/EventType.kt`, `domain/booking/Settings.kt`, `domain/booking/ConnectedCalendar.kt`, `domain/booking/EffectiveRules.kt`
- Create: `application/booking/SettingsRepository.kt`, `application/booking/EventTypeRepository.kt`, `application/booking/ConnectedCalendarRepository.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/domain/booking/EffectiveRulesTest.kt`

(All paths under `time-matcher/src/main/kotlin/io/vladar107/`.)

**Interfaces:**
- Consumes: `AvailabilityRules`, `WeeklyAvailability`, `DateOverride` (domain/availability).
- Produces:
  - `enum class EventTypeStatus { ACTIVE, INACTIVE }`
  - `data class EventType(val id: UUID, val slug: String, val name: String, val duration: Duration, val bufferBefore: Duration, val bufferAfter: Duration, val status: EventTypeStatus)`
  - `data class Settings(val zone: ZoneId, val weekly: WeeklyAvailability, val overrides: List<DateOverride>, val granularity: Duration, val minimumNotice: Duration)`
  - `data class ConnectedCalendar(val id: UUID, val name: String, val provider: String)`
  - `fun EventType.effectiveRules(settings: Settings): AvailabilityRules`
  - `interface SettingsRepository { suspend fun load(): Settings; suspend fun save(settings: Settings) }`
  - `interface EventTypeRepository { suspend fun create(eventType: EventType); suspend fun list(): List<EventType>; suspend fun findBySlug(slug: String): EventType? }`
  - `interface ConnectedCalendarRepository { suspend fun list(): List<ConnectedCalendar>; suspend fun default(): ConnectedCalendar }`

- [ ] **Step 1: Write the failing test** — `EffectiveRulesTest.kt`:
```kotlin
package io.vladar107.domain.booking

import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class EffectiveRulesTest {
    @Test fun mergesSettingsWithEventTypeDurationAndBuffers() {
        val settings = Settings(
            ZoneId.of("Europe/Paris"),
            WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
            emptyList(), Duration.ofMinutes(30), Duration.ofHours(2),
        )
        val et = EventType(UUID.randomUUID(), "intro", "Intro", Duration.ofMinutes(45),
            Duration.ofMinutes(10), Duration.ofMinutes(5), EventTypeStatus.ACTIVE)
        val rules = et.effectiveRules(settings)
        assertEquals(settings.zone, rules.zone)
        assertEquals(settings.weekly, rules.weekly)
        assertEquals(settings.granularity, rules.granularity)
        assertEquals(settings.minimumNotice, rules.minimumNotice)
        assertEquals(Duration.ofMinutes(10), rules.bufferBefore)
        assertEquals(Duration.ofMinutes(5), rules.bufferAfter)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `… --tests "io.vladar107.domain.booking.EffectiveRulesTest"` → FAIL (unresolved).

- [ ] **Step 3: Implement domain + ports**

`EventType.kt`:
```kotlin
package io.vladar107.domain.booking

import java.time.Duration
import java.util.UUID

enum class EventTypeStatus { ACTIVE, INACTIVE }

data class EventType(
    val id: UUID, val slug: String, val name: String,
    val duration: Duration, val bufferBefore: Duration, val bufferAfter: Duration, val status: EventTypeStatus,
)
```
`Settings.kt`:
```kotlin
package io.vladar107.domain.booking

import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.WeeklyAvailability
import java.time.Duration
import java.time.ZoneId

data class Settings(
    val zone: ZoneId, val weekly: WeeklyAvailability, val overrides: List<DateOverride>,
    val granularity: Duration, val minimumNotice: Duration,
)
```
`ConnectedCalendar.kt`:
```kotlin
package io.vladar107.domain.booking

import java.util.UUID

data class ConnectedCalendar(val id: UUID, val name: String, val provider: String)
```
`EffectiveRules.kt`:
```kotlin
package io.vladar107.domain.booking

import io.vladar107.domain.availability.AvailabilityRules

/** Engine input for an event type: host-global Settings + this type's buffers. */
fun EventType.effectiveRules(settings: Settings): AvailabilityRules = AvailabilityRules(
    zone = settings.zone, weekly = settings.weekly, overrides = settings.overrides,
    granularity = settings.granularity, bufferBefore = bufferBefore, bufferAfter = bufferAfter,
    minimumNotice = settings.minimumNotice,
)
```
`SettingsRepository.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.Settings

interface SettingsRepository { suspend fun load(): Settings; suspend fun save(settings: Settings) }
```
`EventTypeRepository.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType

interface EventTypeRepository {
    suspend fun create(eventType: EventType)
    suspend fun list(): List<EventType>
    suspend fun findBySlug(slug: String): EventType?
}
```
`ConnectedCalendarRepository.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.ConnectedCalendar

interface ConnectedCalendarRepository { suspend fun list(): List<ConnectedCalendar>; suspend fun default(): ConnectedCalendar }
```

- [ ] **Step 4: Verify pass + build + commit** — focused test PASS, then `… build`.
```bash
git add time-matcher/src/main/kotlin/io/vladar107/domain/booking/ \
        time-matcher/src/main/kotlin/io/vladar107/application/booking/ \
        time-matcher/src/test/kotlin/io/vladar107/domain/booking/EffectiveRulesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add booking domain types, repository ports, and effective-rules assembly"
```

---

### Task 3: Calendar events + CalendarWriter port + in-memory adapter stores events

**Files:**
- Create: `domain/availability/CalendarEvent.kt`, `application/availability/CalendarWriter.kt`
- Modify: `data/repositories/InMemoryCalendarProvider.kt`
- Modify (test): `time-matcher/src/test/kotlin/io/vladar107/data/repositories/InMemoryCalendarProviderTest.kt`

**Interfaces:**
- Produces:
  - `data class CalendarEvent(val interval: TimeInterval, val title: String, val attendeeName: String? = null, val attendeeEmail: String? = null)`
  - `interface CalendarWriter { suspend fun createEvent(calendarId: String, event: CalendarEvent) }`
  - `InMemoryCalendarProvider` also implements `CalendarWriter`; stores `CalendarEvent`s; `addBusy` → titleless "(busy)" event; `busyIntervals` derives from stored events.

- [ ] **Step 1: Add the failing test** — append to `InMemoryCalendarProviderTest.kt` (keep the existing union test):
```kotlin
    @Test
    fun createdEventShowsAsBusy() = kotlinx.coroutines.runBlocking {
        val provider = InMemoryCalendarProvider()
        provider.createEvent("work", io.vladar107.domain.availability.CalendarEvent(
            TimeInterval(java.time.Instant.parse("2030-01-07T10:00:00Z"), java.time.Instant.parse("2030-01-07T11:00:00Z")),
            title = "Intro with Sam", attendeeName = "Sam", attendeeEmail = "sam@example.com"))
        val window = TimeInterval(java.time.Instant.parse("2030-01-07T00:00:00Z"), java.time.Instant.parse("2030-01-07T23:59:59Z"))
        val busy = provider.busyIntervals(window)
        assertEquals(1, busy.size)
        assertEquals("work", busy.single().calendarId)
    }
```

- [ ] **Step 2: Run to verify it fails** — `… --tests "io.vladar107.data.repositories.InMemoryCalendarProviderTest"` → FAIL.

- [ ] **Step 3: Implement CalendarEvent + CalendarWriter**

`CalendarEvent.kt`:
```kotlin
package io.vladar107.domain.availability

/** An entry on a calendar. A booking is a CalendarEvent with an attendee; manual busy has none. */
data class CalendarEvent(
    val interval: TimeInterval, val title: String,
    val attendeeName: String? = null, val attendeeEmail: String? = null,
)
```
`CalendarWriter.kt`:
```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.CalendarEvent

interface CalendarWriter { suspend fun createEvent(calendarId: String, event: CalendarEvent) }
```

- [ ] **Step 4: Refactor `InMemoryCalendarProvider`**
```kotlin
package io.vladar107.data.repositories

import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.TimeInterval
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory calendar store. Holds events per calendarId; busy is derived. Bind as a Kodein singleton. */
class InMemoryCalendarProvider : CalendarProvider, CalendarBusyWriter, CalendarWriter {
    private val byCalendar = ConcurrentHashMap<String, CopyOnWriteArrayList<CalendarEvent>>()

    override suspend fun addBusy(calendarId: String, interval: TimeInterval) =
        createEvent(calendarId, CalendarEvent(interval, title = "(busy)"))

    override suspend fun createEvent(calendarId: String, event: CalendarEvent) {
        byCalendar.computeIfAbsent(calendarId) { CopyOnWriteArrayList() }.add(event)
    }

    override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> =
        byCalendar.flatMap { (calendarId, events) ->
            events.filter { it.interval.overlaps(window) }.map { BusyInterval(it.interval, calendarId) }
        }
}
```
(`CopyOnWriteArrayList` also closes the Phase-1 Minor about unsynchronized writes.)

- [ ] **Step 5: Verify pass + build + commit** — both calendar tests PASS, then `… build`.
```bash
git add time-matcher/src/main/kotlin/io/vladar107/domain/availability/CalendarEvent.kt \
        time-matcher/src/main/kotlin/io/vladar107/application/availability/CalendarWriter.kt \
        time-matcher/src/main/kotlin/io/vladar107/data/repositories/InMemoryCalendarProvider.kt \
        time-matcher/src/test/kotlin/io/vladar107/data/repositories/InMemoryCalendarProviderTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: store calendar events and add CalendarWriter so bookings show as busy"
```

---

### Task 4: Exposed repositories (Settings, EventType, ConnectedCalendar)

**Files:**
- Create: `data/repositories/ExposedSettingsRepository.kt`, `ExposedEventTypeRepository.kt`, `ExposedConnectedCalendarRepository.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/data/repositories/ExposedRepositoriesTest.kt`

**Interfaces:**
- Consumes: ports + domain (Task 2); `Db`, tables (Task 1).
- Produces: `ExposedSettingsRepository`, `ExposedEventTypeRepository`, `ExposedConnectedCalendarRepository` (no-arg constructors; use the globally-connected Exposed `Database`).

- [ ] **Step 1: Write the failing integration test** — `ExposedRepositoriesTest.kt`:
```kotlin
package io.vladar107.data.repositories

import io.vladar107.data.persistence.Db
import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.EventType
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.domain.booking.Settings
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExposedRepositoriesTest {
    @BeforeTest fun setup() { Db.init("jdbc:h2:mem:repos-${UUID.randomUUID()};DB_CLOSE_DELAY=-1") }

    @Test fun settingsRoundTrips() = runBlocking {
        val repo = ExposedSettingsRepository()
        assertEquals(ZoneId.of("Europe/Paris"), repo.load().zone) // seeded
        val updated = Settings(ZoneId.of("UTC"),
            WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
            listOf(DateOverride(LocalDate.parse("2030-12-25"), emptyList())), Duration.ofMinutes(15), Duration.ofHours(1))
        repo.save(updated)
        val reloaded = repo.load()
        assertEquals(ZoneId.of("UTC"), reloaded.zone)
        assertEquals(Duration.ofMinutes(15), reloaded.granularity)
        assertEquals(listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))), reloaded.weekly.rangesFor(DayOfWeek.MONDAY))
        assertEquals(1, reloaded.overrides.size)
    }

    @Test fun eventTypeCreateListFind() = runBlocking {
        val repo = ExposedEventTypeRepository()
        repo.create(EventType(UUID.randomUUID(), "intro", "Intro", Duration.ofMinutes(30), Duration.ZERO, Duration.ZERO, EventTypeStatus.ACTIVE))
        assertEquals(1, repo.list().size)
        assertEquals("Intro", repo.findBySlug("intro")?.name)
        assertNull(repo.findBySlug("nope"))
    }

    @Test fun connectedCalendarHasSeededDefault() = runBlocking {
        assertEquals("IN_MEMORY", ExposedConnectedCalendarRepository().default().provider)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL (unresolved repos).

- [ ] **Step 3: Implement the repositories**

`ExposedSettingsRepository.kt`:
```kotlin
package io.vladar107.data.repositories

import io.vladar107.application.booking.SettingsRepository
import io.vladar107.data.persistence.DateOverrideTable
import io.vladar107.data.persistence.SettingsTable
import io.vladar107.data.persistence.WorkingHoursTable
import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.Settings
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class ExposedSettingsRepository : SettingsRepository {
    override suspend fun load(): Settings = transaction {
        val row = SettingsTable.selectAll().single()
        val weekly = WorkingHoursTable.selectAll().groupBy { DayOfWeek.valueOf(it[WorkingHoursTable.dayOfWeek]) }
            .mapValues { (_, rows) -> rows.map { LocalTimeRange(LocalTime.parse(it[WorkingHoursTable.startTime]), LocalTime.parse(it[WorkingHoursTable.endTime])) } }
        val overrides = DateOverrideTable.selectAll().map {
            val s = it[DateOverrideTable.startTime]; val e = it[DateOverrideTable.endTime]
            DateOverride(LocalDate.parse(it[DateOverrideTable.date]),
                if (s != null && e != null) listOf(LocalTimeRange(LocalTime.parse(s), LocalTime.parse(e))) else emptyList())
        }
        Settings(ZoneId.of(row[SettingsTable.zone]), WeeklyAvailability(weekly), overrides,
            Duration.ofMinutes(row[SettingsTable.granularityMinutes].toLong()),
            Duration.ofMinutes(row[SettingsTable.minimumNoticeMinutes].toLong()))
    }

    override suspend fun save(settings: Settings): Unit = transaction {
        SettingsTable.update({ SettingsTable.id eq 1 }) {
            it[zone] = settings.zone.id
            it[granularityMinutes] = settings.granularity.toMinutes().toInt()
            it[minimumNoticeMinutes] = settings.minimumNotice.toMinutes().toInt()
        }
        WorkingHoursTable.deleteAll()
        settings.weekly.byDay.forEach { (day, ranges) ->
            ranges.forEach { r -> WorkingHoursTable.insert {
                it[id] = UUID.randomUUID(); it[dayOfWeek] = day.name; it[startTime] = r.start.toString(); it[endTime] = r.end.toString() } }
        }
        DateOverrideTable.deleteAll()
        settings.overrides.forEach { o -> DateOverrideTable.insert {
            it[id] = UUID.randomUUID(); it[date] = o.date.toString()
            it[startTime] = o.ranges.firstOrNull()?.start?.toString(); it[endTime] = o.ranges.firstOrNull()?.end?.toString() } }
    }
}
```
(`WeeklyAvailability.byDay` + `rangesFor(day)` exist from Phase 1. An override row with null times = unavailable. One range per override row is sufficient for 2a.)

`ExposedEventTypeRepository.kt`:
```kotlin
package io.vladar107.data.repositories

import io.vladar107.application.booking.EventTypeRepository
import io.vladar107.data.persistence.EventTypeTable
import io.vladar107.domain.booking.EventType
import io.vladar107.domain.booking.EventTypeStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration

class ExposedEventTypeRepository : EventTypeRepository {
    private fun map(r: ResultRow) = EventType(
        r[EventTypeTable.id], r[EventTypeTable.slug], r[EventTypeTable.name],
        Duration.ofMinutes(r[EventTypeTable.durationMinutes].toLong()),
        Duration.ofMinutes(r[EventTypeTable.bufferBeforeMinutes].toLong()),
        Duration.ofMinutes(r[EventTypeTable.bufferAfterMinutes].toLong()),
        EventTypeStatus.valueOf(r[EventTypeTable.status]))

    override suspend fun create(eventType: EventType): Unit = transaction {
        EventTypeTable.insert {
            it[id] = eventType.id; it[slug] = eventType.slug; it[name] = eventType.name
            it[durationMinutes] = eventType.duration.toMinutes().toInt()
            it[bufferBeforeMinutes] = eventType.bufferBefore.toMinutes().toInt()
            it[bufferAfterMinutes] = eventType.bufferAfter.toMinutes().toInt()
            it[status] = eventType.status.name
        }
    }
    override suspend fun list(): List<EventType> = transaction { EventTypeTable.selectAll().map(::map) }
    override suspend fun findBySlug(slug: String): EventType? = transaction {
        EventTypeTable.selectAll().where { EventTypeTable.slug eq slug }.singleOrNull()?.let(::map)
    }
}
```
`ExposedConnectedCalendarRepository.kt`:
```kotlin
package io.vladar107.data.repositories

import io.vladar107.application.booking.ConnectedCalendarRepository
import io.vladar107.data.persistence.ConnectedCalendarTable
import io.vladar107.domain.booking.ConnectedCalendar
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedConnectedCalendarRepository : ConnectedCalendarRepository {
    private fun map(r: ResultRow) = ConnectedCalendar(r[ConnectedCalendarTable.id], r[ConnectedCalendarTable.name], r[ConnectedCalendarTable.provider])
    override suspend fun list(): List<ConnectedCalendar> = transaction { ConnectedCalendarTable.selectAll().map(::map) }
    override suspend fun default(): ConnectedCalendar = transaction { ConnectedCalendarTable.selectAll().first().let(::map) }
}
```
(`selectAll().where { }` / `singleOrNull()` spelling follows the Exposed version pinned in Task 1.)

- [ ] **Step 4: Verify pass + build + commit**
```bash
git add time-matcher/src/main/kotlin/io/vladar107/data/repositories/Exposed*.kt \
        time-matcher/src/test/kotlin/io/vladar107/data/repositories/ExposedRepositoriesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add Exposed-backed settings, event-type, connected-calendar repositories"
```

---

### Task 5: Migrate config to the DB (Settings command, finder rewire, DI, controller) — keeps build green

Replace Phase 1's in-memory `AvailabilityConfigRepository` with the DB-backed `SettingsRepository` end to end: a `SetSettingsCommand`, the generic finder sourced from Settings, **all DI rewiring**, the `PUT /availability/config` controller/DTO, runtime DB init in file mode, and removal of the obsolete config classes. This task touches DI + controller precisely so the full build stays green.

**Files:**
- Create: `application/booking/SetSettingsCommandHandler.kt`
- Modify: `application/availability/FindAvailableSlotsQueryHandler.kt`
- Modify: `web/availability/dto/AvailabilityConfigRequest.kt` (drop buffers; build `Settings`), `web/availability/AvailabilityController.kt` (persist Settings via `SetSettingsCommand`)
- Modify: `web/di/ConfigureExternalServices.kt` (DB init from config + Clock), `ConfigureRepositories.kt` (settings/event-type/connected-calendar + CalendarWriter singletons; drop the old config binding), `ConfigureQueries.kt` (finder now takes `SettingsRepository`), `ConfigureCommands.kt` (drop `SetAvailabilityRules`, add `SetSettings`), `web/di/ConfigureDi.kt` (pass `Application` to `configureExternalServices`)
- Modify: `src/main/resources/application.yaml` (`db.url` file mode), `.gitignore` (`data/`)
- Delete: `application/availability/AvailabilityConfigRepository.kt`, `data/repositories/InMemoryAvailabilityConfigRepository.kt`, `application/availability/SetAvailabilityRulesCommandHandler.kt`
- Modify (test): `test/.../application/availability/FindAvailableSlotsQueryHandlerTest.kt`
- Create (test): `test/.../application/booking/SetSettingsCommandTest.kt`, `test/.../web/availability/SettingsPersistenceTest.kt`

**Interfaces:**
- Produces:
  - `data class SetSettingsCommand(val settings: Settings) : Command<Unit>` + `SetSettingsCommandHandler(settingsRepository)`.
  - `FindAvailableSlotsQueryHandler(calendarProvider, settingsRepository, clock, engine = AvailabilityEngine())` → builds rules from Settings with `bufferBefore=ZERO, bufferAfter=ZERO`, duration from the query; returns `AvailableSlots(settings.zone, slots)` (unchanged result type).
  - `AvailabilityConfigRequest.toSettings(): Settings`.
  - `fun DI.MainBuilder.configureExternalServices(application: Application)`.

- [ ] **Step 1: Failing unit tests**

`SetSettingsCommandTest.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.Settings
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class SetSettingsCommandTest {
    @Test fun savesSettings() = runBlocking {
        var saved: Settings? = null
        val repo = object : SettingsRepository {
            override suspend fun load() = error("unused"); override suspend fun save(settings: Settings) { saved = settings } }
        val s = Settings(ZoneId.of("UTC"), WeeklyAvailability(emptyMap()), emptyList(), Duration.ofMinutes(30), Duration.ZERO)
        SetSettingsCommandHandler(repo).handle(SetSettingsCommand(s))
        assertEquals(s, saved)
    }
}
```
Rewrite `FindAvailableSlotsQueryHandlerTest.kt` to use a fake `SettingsRepository`:
```kotlin
package io.vladar107.application.availability

import io.vladar107.application.booking.SettingsRepository
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.Settings
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class FindAvailableSlotsQueryHandlerTest {
    private fun t(s: String) = Instant.parse(s)
    private val settings = Settings(ZoneId.of("UTC"),
        WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
        emptyList(), Duration.ofMinutes(30), Duration.ZERO)
    private val settingsRepo = object : SettingsRepository {
        override suspend fun load() = settings; override suspend fun save(settings: Settings) = error("unused") }
    private val provider = object : CalendarProvider {
        override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> = emptyList() }

    @Test fun returnsSlotsInConfiguredZone() = runBlocking {
        val handler = FindAvailableSlotsQueryHandler(provider, settingsRepo, Clock.fixed(t("2020-01-01T00:00:00Z"), ZoneId.of("UTC")))
        val result = handler.handle(FindAvailableSlotsQuery(t("2030-01-07T00:00:00Z"), t("2030-01-07T23:59:59Z"), Duration.ofMinutes(60)))
        assertEquals(ZoneId.of("UTC"), result.zone)
        assertEquals(t("2030-01-07T09:00:00Z"), result.slots.first().start)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `… --tests "io.vladar107.application.booking.SetSettingsCommandTest"` → FAIL.

- [ ] **Step 3: Implement command + finder rewire**

`SetSettingsCommandHandler.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.Settings
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler

data class SetSettingsCommand(val settings: Settings) : Command<Unit>

class SetSettingsCommandHandler(private val settingsRepository: SettingsRepository) : CommandHandler<Unit, SetSettingsCommand> {
    override suspend fun handle(command: SetSettingsCommand) = settingsRepository.save(command.settings)
}
```
Rewrite `FindAvailableSlotsQueryHandler.kt`:
```kotlin
package io.vladar107.application.availability

import io.vladar107.application.booking.SettingsRepository
import io.vladar107.domain.availability.AvailabilityEngine
import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.domain.availability.SlotSearch
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class FindAvailableSlotsQuery(val from: Instant, val to: Instant, val duration: Duration) : Query<AvailableSlots>

class FindAvailableSlotsQueryHandler(
    private val calendarProvider: CalendarProvider,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : QueryHandler<AvailableSlots, FindAvailableSlotsQuery> {
    override suspend fun handle(query: FindAvailableSlotsQuery): AvailableSlots {
        val s = settingsRepository.load()
        val rules = AvailabilityRules(s.zone, s.weekly, s.overrides, s.granularity, Duration.ZERO, Duration.ZERO, s.minimumNotice)
        val busy = calendarProvider.busyIntervals(TimeInterval(query.from, query.to))
        val slots = engine.findSlots(rules, busy, SlotSearch(query.from, query.to, query.duration), clock.instant())
        return AvailableSlots(s.zone, slots)
    }
}
```
Delete `AvailabilityConfigRepository.kt`, `InMemoryAvailabilityConfigRepository.kt`, `SetAvailabilityRulesCommandHandler.kt`.

- [ ] **Step 4: Update the config DTO + controller**

Rewrite `AvailabilityConfigRequest.kt` (drop buffers; build `Settings`; keep `LocalTimeRangeDto`/`DateOverrideDto`):
```kotlin
package io.vladar107.web.availability.dto

import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.Settings
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable data class LocalTimeRangeDto(val start: String, val end: String)
@Serializable data class DateOverrideDto(val date: String, val ranges: List<LocalTimeRangeDto>)

@Serializable
data class AvailabilityConfigRequest(
    val zone: String, val granularityMinutes: Long, val minimumNoticeMinutes: Long = 0,
    val weekly: Map<String, List<LocalTimeRangeDto>> = emptyMap(),
    val overrides: List<DateOverrideDto> = emptyList(),
) {
    fun toSettings(): Settings {
        require(granularityMinutes > 0) { "granularityMinutes must be positive" }
        require(minimumNoticeMinutes >= 0) { "minimumNoticeMinutes must be non-negative" }
        fun range(d: LocalTimeRangeDto) = LocalTimeRange(LocalTime.parse(d.start), LocalTime.parse(d.end))
        return Settings(
            zone = ZoneId.of(zone),
            weekly = WeeklyAvailability(weekly.entries.associate { (day, r) -> DayOfWeek.valueOf(day.uppercase()) to r.map(::range) }),
            overrides = overrides.map { DateOverride(LocalDate.parse(it.date), it.ranges.map(::range)) },
            granularity = Duration.ofMinutes(granularityMinutes), minimumNotice = Duration.ofMinutes(minimumNoticeMinutes),
        )
    }
}
```
In `AvailabilityController.kt` change the `PUT /availability/config` body to build/run `SetSettingsCommand(call.receive<AvailabilityConfigRequest>().toSettings())` inside the existing try/catch→400, respond `204`. Update imports (drop `SetAvailabilityRulesCommand`; add `SetSettingsCommand` + `AvailabilityConfigRequest`). Leave `GET /availability/slots` and `POST /calendars/{id}/busy` unchanged (the `AvailableSlots`→`SlotDto` mapping is unchanged).

- [ ] **Step 5: Rewire DI + runtime DB (file mode)**

`ConfigureExternalServices.kt`:
```kotlin
package io.vladar107.web.di

import io.ktor.server.application.*
import io.vladar107.data.persistence.Db
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import java.time.Clock

fun DI.MainBuilder.configureExternalServices(application: Application) {
    val url = application.environment.config.propertyOrNull("db.url")?.getString()
        ?: "jdbc:h2:file:./data/timematcher;DB_CLOSE_DELAY=-1"
    Db.init(url)
    bind<Clock>() with singleton { Clock.systemDefaultZone() }
}
```
In `ConfigureDi.kt`, change the call to `configureExternalServices(this@configureDi)`.
`ConfigureRepositories.kt` (keep the `UserCreationRepository` binding; replace the old config binding):
```kotlin
    bind<InMemoryCalendarProvider>() with singleton { InMemoryCalendarProvider() }
    bind<CalendarProvider>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<CalendarBusyWriter>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<CalendarWriter>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<SettingsRepository>() with singleton { ExposedSettingsRepository() }
    bind<EventTypeRepository>() with singleton { ExposedEventTypeRepository() }
    bind<ConnectedCalendarRepository>() with singleton { ExposedConnectedCalendarRepository() }
```
`ConfigureQueries.kt` — rebind the finder with the settings repo (and keep it returning `AvailableSlots`):
```kotlin
    bind<QueryHandler<AvailableSlots, FindAvailableSlotsQuery>>() with provider {
        FindAvailableSlotsQueryHandler(instance(), instance(), instance())
    }
```
`ConfigureCommands.kt` — keep the user command; replace the settings command:
```kotlin
    bind<CommandHandler<Unit, SetSettingsCommand>>() with provider { SetSettingsCommandHandler(instance()) }
```
`application.yaml` — add:
```yaml
db:
    url: "jdbc:h2:file:./data/timematcher;DB_CLOSE_DELAY=-1"
```
`.gitignore` — add `data/`.

- [ ] **Step 6: Integration test — config persists + finder works on the DB**

`SettingsPersistenceTest.kt` (override `db.url` to a unique in-memory DB):
```kotlin
package io.vladar107.web.availability

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.vladar107.module
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPersistenceTest {
    @Test fun putConfigPersistsAndDrivesSlots() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:settings-test;DB_CLOSE_DELAY=-1") }
        application { module() }
        val put = client.put("/availability/config") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"zone":"UTC","granularityMinutes":60,"weekly":{"MONDAY":[{"start":"09:00","end":"17:00"}]},"overrides":[]}""")
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        val slots = client.get("/availability/slots") {
            parameter("from", "2030-01-07T00:00:00Z"); parameter("to", "2030-01-07T23:59:59Z"); parameter("duration", "PT1H")
        }
        assertEquals(HttpStatusCode.OK, slots.status)
        assert(slots.bodyAsText().contains("2030-01-07T09:00:00Z"))
    }
}
```

- [ ] **Step 7: Full build (green) + commit**

Run: `… gradlew -p … build` → BUILD SUCCESSFUL (all suites). Commit:
```bash
git add -A time-matcher/src/main/kotlin/io/vladar107 time-matcher/src/main/resources/application.yaml \
          time-matcher/.gitignore time-matcher/src/test/kotlin/io/vladar107
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "refactor: migrate settings to DB (SetSettings + finder + DI + file-mode H2)"
```

---

### Task 6: EventType endpoints (use cases + DI + controller)

Adds the event-type use cases, binds them, and exposes host endpoints. Ends green with an integration test.

**Files:**
- Create: `application/booking/CreateEventTypeCommandHandler.kt`, `ListEventTypesQueryHandler.kt`, `GetEventTypeBySlugQueryHandler.kt`
- Create: `web/booking/dto/EventTypeDto.kt`, `web/booking/EventTypeController.kt`
- Modify: `web/di/ConfigureCommands.kt`, `ConfigureQueries.kt`, `Application.kt` (register `configureEventTypes`)
- Test: `test/.../web/booking/EventTypeRoutesTest.kt`

**Interfaces:**
- Produces:
  - `data class CreateEventTypeCommand(slug, name, duration, bufferBefore, bufferAfter) : Command<Unit>` + handler (assigns `UUID.randomUUID()`, status `ACTIVE`).
  - `class ListEventTypesQuery : Query<List<EventType>>` + handler; `data class GetEventTypeBySlugQuery(slug) : Query<EventType?>` + handler.
  - `fun Application.configureEventTypes()`.

- [ ] **Step 1: Failing integration test** — `EventTypeRoutesTest.kt`:
```kotlin
package io.vladar107.web.booking

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.vladar107.module
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventTypeRoutesTest {
    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test fun createsAndListsEventTypes() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:et-test;DB_CLOSE_DELAY=-1") }
        application { module() }
        val client = jsonClient()
        val create = client.post("/event-types") {
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"intro","name":"Intro call","durationMinutes":30,"bufferBeforeMinutes":0,"bufferAfterMinutes":0}""")
        }
        assertEquals(HttpStatusCode.Created, create.status)
        assertTrue(client.get("/event-types").bodyAsText().contains("intro"))
        assertEquals(HttpStatusCode.OK, client.get("/event-types/intro").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/event-types/missing").status)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement the use cases**

`CreateEventTypeCommandHandler.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler
import java.time.Duration
import java.util.UUID

data class CreateEventTypeCommand(
    val slug: String, val name: String, val duration: Duration, val bufferBefore: Duration, val bufferAfter: Duration,
) : Command<Unit>

class CreateEventTypeCommandHandler(private val repository: EventTypeRepository) : CommandHandler<Unit, CreateEventTypeCommand> {
    override suspend fun handle(command: CreateEventTypeCommand) = repository.create(
        EventType(UUID.randomUUID(), command.slug, command.name, command.duration, command.bufferBefore, command.bufferAfter, EventTypeStatus.ACTIVE))
}
```
`ListEventTypesQueryHandler.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler

class ListEventTypesQuery : Query<List<EventType>>
class ListEventTypesQueryHandler(private val repository: EventTypeRepository) : QueryHandler<List<EventType>, ListEventTypesQuery> {
    override suspend fun handle(query: ListEventTypesQuery): List<EventType> = repository.list()
}
```
`GetEventTypeBySlugQueryHandler.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler

data class GetEventTypeBySlugQuery(val slug: String) : Query<EventType?>
class GetEventTypeBySlugQueryHandler(private val repository: EventTypeRepository) : QueryHandler<EventType?, GetEventTypeBySlugQuery> {
    override suspend fun handle(query: GetEventTypeBySlugQuery): EventType? = repository.findBySlug(query.slug)
}
```

- [ ] **Step 4: DTO + controller + DI + register**

`EventTypeDto.kt`:
```kotlin
package io.vladar107.web.booking.dto

import kotlinx.serialization.Serializable

@Serializable data class CreateEventTypeRequest(
    val slug: String, val name: String, val durationMinutes: Long, val bufferBeforeMinutes: Long = 0, val bufferAfterMinutes: Long = 0)
@Serializable data class EventTypeDto(
    val id: String, val slug: String, val name: String,
    val durationMinutes: Long, val bufferBeforeMinutes: Long, val bufferAfterMinutes: Long, val status: String)
```
`EventTypeController.kt`:
```kotlin
package io.vladar107.web.booking

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.vladar107.application.booking.CreateEventTypeCommand
import io.vladar107.application.booking.GetEventTypeBySlugQuery
import io.vladar107.application.booking.ListEventTypesQuery
import io.vladar107.domain.booking.EventType
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.web.booking.dto.CreateEventTypeRequest
import io.vladar107.web.booking.dto.EventTypeDto
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.time.Duration

private fun EventType.toDto() = EventTypeDto(id.toString(), slug, name, duration.toMinutes(), bufferBefore.toMinutes(), bufferAfter.toMinutes(), status.name)

fun Application.configureEventTypes() {
    val commandProvider by closestDI { this@configureEventTypes }.instance<CommandProvider>()
    val queryProvider by closestDI { this@configureEventTypes }.instance<QueryProvider>()
    routing {
        route("/event-types") {
            post {
                val cmd = try {
                    val b = call.receive<CreateEventTypeRequest>()
                    require(b.slug.isNotBlank() && b.name.isNotBlank()) { "slug and name required" }
                    require(b.durationMinutes > 0) { "durationMinutes must be positive" }
                    require(b.bufferBeforeMinutes >= 0 && b.bufferAfterMinutes >= 0) { "buffers must be non-negative" }
                    CreateEventTypeCommand(b.slug, b.name, Duration.ofMinutes(b.durationMinutes), Duration.ofMinutes(b.bufferBeforeMinutes), Duration.ofMinutes(b.bufferAfterMinutes))
                } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid event type: ${e.message}") }
                commandProvider.run(cmd); call.respond(HttpStatusCode.Created)
            }
            get { call.respond(queryProvider.query(ListEventTypesQuery()).map { it.toDto() }) }
            get("/{slug}") {
                val et: EventType? = queryProvider.query(GetEventTypeBySlugQuery(call.parameters["slug"]!!))
                if (et == null) call.respond(HttpStatusCode.NotFound, "Unknown event type") else call.respond(et.toDto())
            }
        }
    }
}
```
DI: in `ConfigureCommands.kt` add `bind<CommandHandler<Unit, CreateEventTypeCommand>>() with provider { CreateEventTypeCommandHandler(instance()) }`; in `ConfigureQueries.kt` add `bind<QueryHandler<List<EventType>, ListEventTypesQuery>>() with provider { ListEventTypesQueryHandler(instance()) }` and `bind<QueryHandler<EventType?, GetEventTypeBySlugQuery>>() with provider { GetEventTypeBySlugQueryHandler(instance()) }`. In `Application.kt` add `configureEventTypes()` to `module()`.

- [ ] **Step 5: Full build (green) + commit**
```bash
git add time-matcher/src/main/kotlin/io/vladar107/application/booking \
        time-matcher/src/main/kotlin/io/vladar107/web/booking time-matcher/src/main/kotlin/io/vladar107/web/di \
        time-matcher/src/main/kotlin/io/vladar107/Application.kt \
        time-matcher/src/test/kotlin/io/vladar107/web/booking/EventTypeRoutesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: event-type create/list/get use cases + host endpoints"
```

---

### Task 7: Booking endpoints (per-type slots + book with double-booking guard)

**Files:**
- Create: `application/booking/FindEventTypeSlotsQueryHandler.kt`, `BookSlotCommandHandler.kt`
- Create: `web/booking/dto/BookingDto.kt`, `web/booking/BookingController.kt`
- Modify: `web/di/ConfigureQueries.kt`, `ConfigureCommands.kt`, `Application.kt` (register `configureBooking`)
- Test: `test/.../application/booking/BookSlotCommandTest.kt`, `test/.../web/booking/BookingRoutesTest.kt`

**Interfaces:**
- Produces:
  - `data class FindEventTypeSlotsQuery(slug, from, to) : Query<AvailableSlots?>` + handler (null if missing/inactive).
  - `sealed interface BookingResult { data class Booked(start, end, eventTypeName, zone) ; data object SlotTaken ; data object EventTypeNotFound }`
  - `data class BookSlotCommand(slug, attendeeName, attendeeEmail, start) : Command<BookingResult>` + `BookSlotCommandHandler(...)` — **bound as singleton** (holds a `Mutex`).
  - `fun Application.configureBooking()`.

- [ ] **Step 1: Failing unit test** — `BookSlotCommandTest.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.ConnectedCalendar
import io.vladar107.domain.booking.EventType
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.domain.booking.Settings
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookSlotCommandTest {
    private fun t(s: String) = Instant.parse(s)
    private val zone = ZoneId.of("UTC")
    private val et = EventType(UUID.randomUUID(), "intro", "Intro", Duration.ofMinutes(60), Duration.ZERO, Duration.ZERO, EventTypeStatus.ACTIVE)
    private val settings = Settings(zone,
        WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
        emptyList(), Duration.ofMinutes(30), Duration.ZERO)

    private fun handler(written: CopyOnWriteArrayList<Pair<String, CalendarEvent>>): BookSlotCommandHandler {
        val store = CopyOnWriteArrayList<Pair<String, CalendarEvent>>()
        val provider = object : CalendarProvider {
            override suspend fun busyIntervals(window: TimeInterval) =
                store.filter { it.second.interval.overlaps(window) }.map { BusyInterval(it.second.interval, it.first) } }
        val writer = object : CalendarWriter {
            override suspend fun createEvent(calendarId: String, event: CalendarEvent) { store += calendarId to event; written += calendarId to event } }
        return BookSlotCommandHandler(
            object : EventTypeRepository {
                override suspend fun create(eventType: EventType) {}; override suspend fun list() = listOf(et)
                override suspend fun findBySlug(slug: String) = et.takeIf { it.slug == slug } },
            object : SettingsRepository { override suspend fun load() = settings; override suspend fun save(settings: Settings) {} },
            provider, writer,
            object : ConnectedCalendarRepository {
                override suspend fun list() = listOf(ConnectedCalendar(UUID.randomUUID(), "Default", "IN_MEMORY"))
                override suspend fun default() = ConnectedCalendar(UUID.randomUUID(), "Default", "IN_MEMORY") },
            Clock.fixed(t("2020-01-01T00:00:00Z"), zone))
    }

    @Test fun booksThenSecondBookingOfSameSlotIsTaken() = runBlocking {
        val written = CopyOnWriteArrayList<Pair<String, CalendarEvent>>()
        val h = handler(written)
        assertTrue(h.handle(BookSlotCommand("intro", "Sam", "sam@example.com", t("2030-01-07T09:00:00Z"))) is BookingResult.Booked)
        assertEquals(1, written.size)
        assertTrue(h.handle(BookSlotCommand("intro", "Pat", "pat@example.com", t("2030-01-07T09:00:00Z"))) is BookingResult.SlotTaken)
        assertEquals(1, written.size)
    }

    @Test fun unknownSlugIsNotFound() = runBlocking {
        assertTrue(handler(CopyOnWriteArrayList()).handle(BookSlotCommand("nope", "Sam", "s@e.com", t("2030-01-07T09:00:00Z"))) is BookingResult.EventTypeNotFound)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement the booking use cases**

`FindEventTypeSlotsQueryHandler.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.application.availability.AvailableSlots
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.domain.availability.AvailabilityEngine
import io.vladar107.domain.availability.SlotSearch
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.domain.booking.effectiveRules
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler
import java.time.Clock
import java.time.Instant

data class FindEventTypeSlotsQuery(val slug: String, val from: Instant, val to: Instant) : Query<AvailableSlots?>

class FindEventTypeSlotsQueryHandler(
    private val eventTypeRepository: EventTypeRepository,
    private val settingsRepository: SettingsRepository,
    private val calendarProvider: CalendarProvider,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : QueryHandler<AvailableSlots?, FindEventTypeSlotsQuery> {
    override suspend fun handle(query: FindEventTypeSlotsQuery): AvailableSlots? {
        val et = eventTypeRepository.findBySlug(query.slug)?.takeIf { it.status == EventTypeStatus.ACTIVE } ?: return null
        val settings = settingsRepository.load()
        val busy = calendarProvider.busyIntervals(TimeInterval(query.from, query.to))
        val slots = engine.findSlots(et.effectiveRules(settings), busy, SlotSearch(query.from, query.to, et.duration), clock.instant())
        return AvailableSlots(settings.zone, slots)
    }
}
```
`BookSlotCommandHandler.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.domain.availability.AvailabilityEngine
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.SlotSearch
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.domain.booking.effectiveRules
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

sealed interface BookingResult {
    data class Booked(val start: Instant, val end: Instant, val eventTypeName: String, val zone: ZoneId) : BookingResult
    data object SlotTaken : BookingResult
    data object EventTypeNotFound : BookingResult
}

data class BookSlotCommand(val slug: String, val attendeeName: String, val attendeeEmail: String, val start: Instant) : Command<BookingResult>

class BookSlotCommandHandler(
    private val eventTypeRepository: EventTypeRepository,
    private val settingsRepository: SettingsRepository,
    private val calendarProvider: CalendarProvider,
    private val calendarWriter: CalendarWriter,
    private val connectedCalendarRepository: ConnectedCalendarRepository,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : CommandHandler<BookingResult, BookSlotCommand> {
    private val mutex = Mutex()

    override suspend fun handle(command: BookSlotCommand): BookingResult {
        val et = eventTypeRepository.findBySlug(command.slug)?.takeIf { it.status == EventTypeStatus.ACTIVE }
            ?: return BookingResult.EventTypeNotFound
        val settings = settingsRepository.load()
        val rules = et.effectiveRules(settings)
        val end = command.start.plus(et.duration)
        return mutex.withLock {
            val window = TimeInterval(command.start.minus(et.bufferBefore), end.plus(et.bufferAfter))
            val busy = calendarProvider.busyIntervals(window)
            val open = engine.findSlots(rules, busy, SlotSearch(command.start, end, et.duration), clock.instant())
            if (open.none { it.start == command.start }) BookingResult.SlotTaken
            else {
                val calendarId = connectedCalendarRepository.default().id.toString()
                calendarWriter.createEvent(calendarId,
                    CalendarEvent(TimeInterval(command.start, end), "${et.name} with ${command.attendeeName}", command.attendeeName, command.attendeeEmail))
                BookingResult.Booked(command.start, end, et.name, settings.zone)
            }
        }
    }
}
```

- [ ] **Step 4: Booking DTO + controller + DI + register**

`BookingDto.kt`:
```kotlin
package io.vladar107.web.booking.dto

import kotlinx.serialization.Serializable

@Serializable data class BookRequest(val attendeeName: String, val attendeeEmail: String, val start: String)
@Serializable data class BookingConfirmation(val start: String, val end: String, val eventType: String)
```
`BookingController.kt`:
```kotlin
package io.vladar107.web.booking

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.vladar107.application.availability.AvailableSlots
import io.vladar107.application.booking.BookSlotCommand
import io.vladar107.application.booking.BookingResult
import io.vladar107.application.booking.FindEventTypeSlotsQuery
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.web.availability.dto.SlotDto
import io.vladar107.web.booking.dto.BookRequest
import io.vladar107.web.booking.dto.BookingConfirmation
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")
private fun render(instant: Instant, zone: ZoneId) = OffsetDateTime.ofInstant(instant, zone).format(ISO)

fun Application.configureBooking() {
    val commandProvider by closestDI { this@configureBooking }.instance<CommandProvider>()
    val queryProvider by closestDI { this@configureBooking }.instance<QueryProvider>()
    routing {
        route("/book/{slug}") {
            get("/slots") {
                val slug = call.parameters["slug"]!!
                val query = try {
                    val f = Instant.parse(call.request.queryParameters["from"]); val t = Instant.parse(call.request.queryParameters["to"])
                    require(f.isBefore(t)) { "from must be before to" }; FindEventTypeSlotsQuery(slug, f, t)
                } catch (e: Exception) { return@get call.respond(HttpStatusCode.BadRequest, "Invalid query: ${e.message}") }
                val result: AvailableSlots? = queryProvider.query(query)
                if (result == null) return@get call.respond(HttpStatusCode.NotFound, "Unknown event type")
                call.respond(result.slots.map { SlotDto(render(it.start, result.zone), render(it.end, result.zone)) })
            }
            post {
                val slug = call.parameters["slug"]!!
                val cmd = try {
                    val b = call.receive<BookRequest>()
                    require(b.attendeeName.isNotBlank() && b.attendeeEmail.isNotBlank()) { "attendee required" }
                    BookSlotCommand(slug, b.attendeeName, b.attendeeEmail, Instant.parse(b.start))
                } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid booking: ${e.message}") }
                when (val r: BookingResult = commandProvider.run(cmd)) {
                    is BookingResult.Booked -> call.respond(HttpStatusCode.Created, BookingConfirmation(render(r.start, r.zone), render(r.end, r.zone), r.eventTypeName))
                    BookingResult.SlotTaken -> call.respond(HttpStatusCode.Conflict, "Slot no longer available")
                    BookingResult.EventTypeNotFound -> call.respond(HttpStatusCode.NotFound, "Unknown event type")
                }
            }
        }
    }
}
```
DI: `ConfigureQueries.kt` add `bind<QueryHandler<AvailableSlots?, FindEventTypeSlotsQuery>>() with provider { FindEventTypeSlotsQueryHandler(instance(), instance(), instance(), instance()) }`. `ConfigureCommands.kt` add (singleton — holds the mutex) `bind<CommandHandler<BookingResult, BookSlotCommand>>() with singleton { BookSlotCommandHandler(instance(), instance(), instance(), instance(), instance(), instance()) }`. `Application.kt` add `configureBooking()` to `module()`.

- [ ] **Step 5: Failing integration test, then green** — `BookingRoutesTest.kt`:
```kotlin
package io.vladar107.web.booking

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.vladar107.module
import io.vladar107.web.availability.dto.SlotDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookingRoutesTest {
    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test fun bookingRemovesTheSlotAndSecondBookingConflicts() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:book-test;DB_CLOSE_DELAY=-1") }
        application { module() }
        val client = jsonClient()
        client.put("/availability/config") { contentType(ContentType.Application.Json)
            setBody("""{"zone":"UTC","granularityMinutes":60,"weekly":{"MONDAY":[{"start":"09:00","end":"17:00"}]},"overrides":[]}""") }
        client.post("/event-types") { contentType(ContentType.Application.Json)
            setBody("""{"slug":"intro","name":"Intro","durationMinutes":60}""") }
        val before = Json.decodeFromString<List<SlotDto>>(
            client.get("/book/intro/slots") { parameter("from", "2030-01-07T00:00:00Z"); parameter("to", "2030-01-07T23:59:59Z") }.bodyAsText())
        assertTrue(before.any { it.start == "2030-01-07T09:00:00Z" })
        assertEquals(HttpStatusCode.Created, client.post("/book/intro") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"Sam","attendeeEmail":"sam@example.com","start":"2030-01-07T09:00:00Z"}""") }.status)
        val after = Json.decodeFromString<List<SlotDto>>(
            client.get("/book/intro/slots") { parameter("from", "2030-01-07T00:00:00Z"); parameter("to", "2030-01-07T23:59:59Z") }.bodyAsText())
        assertTrue(after.none { it.start == "2030-01-07T09:00:00Z" })
        assertEquals(HttpStatusCode.Conflict, client.post("/book/intro") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"Pat","attendeeEmail":"pat@example.com","start":"2030-01-07T09:00:00Z"}""") }.status)
    }

    @Test fun bookingUnknownEventTypeIs404() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:book404;DB_CLOSE_DELAY=-1") }
        application { module() }
        assertEquals(HttpStatusCode.NotFound, jsonClient().post("/book/nope") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"X","attendeeEmail":"x@e.com","start":"2030-01-07T09:00:00Z"}""") }.status)
    }
}
```
- [ ] **Step 6: Full build (green) + commit**

Run: `… gradlew -p … build` → BUILD SUCCESSFUL.
```bash
git add time-matcher/src/main/kotlin/io/vladar107/application/booking \
        time-matcher/src/main/kotlin/io/vladar107/web/booking time-matcher/src/main/kotlin/io/vladar107/web/di \
        time-matcher/src/main/kotlin/io/vladar107/Application.kt \
        time-matcher/src/test/kotlin/io/vladar107/application/booking/BookSlotCommandTest.kt \
        time-matcher/src/test/kotlin/io/vladar107/web/booking/BookingRoutesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: public booking endpoints + book command with double-booking guard"
```

---

### Task 8: OpenAPI + ADR + diagram + final build

**Files:**
- Modify: `time-matcher/src/main/resources/openapi/documentation.yaml` and `time-matcher/resources/openapi/documentation.yaml`
- Create: `documentation/adr/20260629-bookings-live-in-the-calendar.md`
- Modify: `documentation/diagrams/Container.md`

- [ ] **Step 1: Document the new endpoints in both OpenAPI files**

Add paths `POST /event-types`, `GET /event-types`, `GET /event-types/{slug}`, `GET /book/{slug}/slots`, `POST /book/{slug}` and schemas `CreateEventTypeRequest`, `EventTypeDto`, `BookRequest`, `BookingConfirmation` (mirroring the controllers; ISO-8601 strings, durations in minutes). Keep the existing availability/user paths and the buffer-less config DTO (`AvailabilityConfigRequest` no longer has buffer fields). Update both files identically.

- [ ] **Step 2: ADR** — `documentation/adr/20260629-bookings-live-in-the-calendar.md`:
```markdown
# Bookings live in the calendar; the DB holds only configuration

- Status: accepted
- Deciders: vladar107
- Date: 2026-06-29
- Tags: architecture, booking, persistence

## Context and Problem Statement
Phase 2 introduces booking. We must decide where bookings and configuration live.

## Decision Outcome
The **calendar is the system of record for bookings** — a booking is an event written to a connected calendar (via the `CalendarWriter` port; in-memory adapter in slice 2a, real Google in 2b), and availability is pulled live from calendars. The **database (H2 file, via Exposed + Flyway) holds only configuration**: settings, event types, connected-calendar records. **Booking logic is in-memory/stateless**: compute open slots, validate under a Mutex, then write the event.

Consequences:
- The Phase-1 `AvailabilityEngine` is unchanged; its `AvailabilityRules` is assembled from host-global `Settings` + a per-`EventType` duration/buffers.
- Double-booking is prevented by serializing validate-then-write with a Mutex (sufficient for a single-process, single-host slice).
- Roles: one host manages settings/event types; anyone with an event type's `slug` (the link) can book. Host authentication is deferred to slice 2c.
- Config persists in an H2 file (`db.url`); tests override it to a unique in-memory DB. `Db.init` connects Exposed globally, so tests run with `maxParallelForks = 1`.
```

- [ ] **Step 3: Diagram note + final build + commit**

Add under the Phase-1 note in `documentation/diagrams/Container.md`:
```markdown
> Phase 2a (implemented): EventTypes + booking. Config (settings, event types, connected calendars) in H2; bookings written to the calendar via the CalendarWriter port. Real Google calendar + host auth are later slices.
```
Run: `… gradlew -p … build` → BUILD SUCCESSFUL (all suites).
```bash
git add time-matcher/src/main/resources/openapi/documentation.yaml time-matcher/resources/openapi/documentation.yaml \
        documentation/adr/20260629-bookings-live-in-the-calendar.md documentation/diagrams/Container.md
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "docs: OpenAPI for event-types/booking; ADR + diagram for Phase 2a"
```

---

## Self-Review

**Spec coverage:** config/settings split → T2/T5; H2 schema + stack + version de-risk → T1; `CalendarWriter` + booking-shows-busy → T3; DB repos → T4; settings command + finder rewire + DB wiring (file mode) → T5; EventType use cases + host endpoints → T6; per-type slots + book + double-booking guard + public endpoints → T7; host vs public routes, auth deferred to 2c, EventTypes create/list/get only → T5–T7 + ADR; engine unchanged → confirmed; integration-weighted tests + focused units → T1/T4/T5/T6/T7 (integration), T2/T3/T7 (units); docs → T8.

**Build-green invariant:** every task ends with a full `./gradlew build`. The only deletions (T5) update all references (DI + controller) in the same task. T6/T7 add handlers and bind+expose them within the same task. No "build red" interval.

**Placeholder scan:** complete code throughout; no TBD/TODO. Logged scope cut: one range per `date_override` row.

**Type consistency:** `Settings(zone, weekly, overrides, granularity, minimumNotice)`, `EventType(id, slug, name, duration, bufferBefore, bufferAfter, status)`, `effectiveRules(settings)`, `CalendarWriter.createEvent(calendarId, CalendarEvent)`, `AvailableSlots(zone, slots)`, `BookingResult` variants, and all repository port signatures are consistent across the tasks that define, adapt, and consume them. DI binding generic types match the handlers' `QueryHandler`/`CommandHandler` parameterizations.

**Cross-version risk (flagged):** Exposed 1.0 module/package/query-DSL names + Flyway H2 module are resolved empirically in T1; later tasks' Exposed imports follow T1's pinned set.
