# Phase 2 Slice 2a — EventTypes + Booking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A host defines EventTypes (persisted in H2); anyone with an EventType's link sees its open slots and books one; the booking is written to the calendar (in-memory adapter) and immediately stops being offered. Configuration persists across restarts; bookings live in the calendar.

**Architecture:** Reuse the Phase-1 pure `AvailabilityEngine` unchanged — assemble its `AvailabilityRules` input from a host-global `Settings` plus a per-`EventType` duration/buffers. Configuration (Settings, EventTypes, connected calendars) persists in H2 via Exposed + Flyway. Bookings are written through a new `CalendarWriter` port to the in-memory calendar (real Google in slice 2b). Booking validation + write run under a lock to prevent double-booking.

**Tech Stack:** Kotlin 2.4, Ktor 3.5.1, Kodein 7.32, H2 (file), Flyway, Exposed (Kotlin SQL DSL), `java.time`, JDK 25, Gradle 9.5.

## Global Constraints

- **All Gradle commands run from `time-matcher/`.** JDK 25 is keg-only: prefix every Gradle call with `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` and target the project dir, e.g.
  `JAVA_HOME=… /Users/vramazaev/src/time-matcher/time-matcher/gradlew -p /Users/vramazaev/src/time-matcher/time-matcher <task>`.
- Source root: `time-matcher/src/main/kotlin/io/vladar107/`. Test root: `time-matcher/src/test/kotlin/io/vladar107/`. Resources: `time-matcher/src/main/resources/`.
- **New dependencies (this slice):** H2 (`com.h2database:h2`), Flyway (`org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-h2`), Exposed (`org.jetbrains.exposed:exposed-core` + `-jdbc` + the java-time module). Versions pinned in `gradle.properties`. **Their exact coordinates/versions MUST be verified empirically against Gradle 9.5 / Kotlin 2.4 / JDK 25 in Task 1** — Exposed 1.0 reorganized its modules/packages; if it causes friction, fall back to the latest Exposed 0.x (`exposed-core`/`exposed-jdbc`/`exposed-java-time`). Record the final working set.
- **Add new `io.ktor:*` deps without a version** (the `ktor-bom` aligns them). This does NOT apply to H2/Flyway/Exposed — pin those.
- **Persistence is config-only.** Booked events are NEVER stored in the DB; they live in the calendar (the in-memory calendar adapter for this slice).
- **The Phase-1 `AvailabilityEngine` is not modified.** Only the assembly of its `AvailabilityRules` input changes.
- **Stateful adapters are Kodein `singleton`** (DB repos, in-memory calendar, the booking lock/handler). Stateless handlers stay `provider`.
- **Commits:** author `Vladislav Ramazaev <vladar107@gmail.com>`, NO co-author trailer:
  `git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" -m "…"`.
- Branch: `feat/eventtypes-booking` (already created). Conventional commit prefixes.
- Validate at the web boundary → 400; trust internal code below it.

---

### Task 1: Persistence foundation (deps + H2 schema + Exposed + Flyway init) — DE-RISK GATE

Stand up the config database and prove the H2/Flyway/Exposed stack works on the toolchain before any feature is built on it.

**Files:**
- Modify: `time-matcher/build.gradle.kts` (deps), `time-matcher/gradle.properties` (versions)
- Create: `time-matcher/src/main/resources/db/migration/V1__init.sql`
- Create: `time-matcher/src/main/kotlin/io/vladar107/data/persistence/Database.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/data/persistence/Tables.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/data/persistence/DatabaseTest.kt`

**Interfaces:**
- Produces:
  - `object Db { fun init(jdbcUrl: String, user: String = "sa", password: String = ""): Database }` — runs Flyway migrate then connects Exposed; returns the Exposed `Database`.
  - Exposed tables in `Tables.kt`: `SettingsTable`, `WorkingHoursTable`, `DateOverrideTable`, `EventTypeTable`, `ConnectedCalendarTable` (column names match `V1__init.sql`).

- [ ] **Step 1: Add dependencies**

In `time-matcher/gradle.properties` append (verify/adjust in Step 4):
```
h2_version=2.3.232
flyway_version=11.9.0
exposed_version=1.0.0
```
In `time-matcher/build.gradle.kts` `dependencies { }` add:
```kotlin
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("com.h2database:h2:$h2_version")
    implementation("org.flywaydb:flyway-core:$flyway_version")
    implementation("org.flywaydb:flyway-database-h2:$flyway_version")
```
And declare the version vals at the top of `build.gradle.kts` next to the existing ones:
```kotlin
val h2_version: String by project
val flyway_version: String by project
val exposed_version: String by project
```

- [ ] **Step 2: Write the schema migration**

`V1__init.sql`:
```sql
CREATE TABLE settings (
    id INT PRIMARY KEY,
    zone VARCHAR(64) NOT NULL,
    granularity_minutes INT NOT NULL,
    minimum_notice_minutes INT NOT NULL
);

CREATE TABLE working_hours (
    id UUID PRIMARY KEY,
    day_of_week VARCHAR(9) NOT NULL,
    start_time VARCHAR(5) NOT NULL,
    end_time VARCHAR(5) NOT NULL
);

CREATE TABLE date_override (
    id UUID PRIMARY KEY,
    override_date VARCHAR(10) NOT NULL,
    start_time VARCHAR(5),
    end_time VARCHAR(5)
);

CREATE TABLE event_type (
    id UUID PRIMARY KEY,
    slug VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(256) NOT NULL,
    duration_minutes INT NOT NULL,
    buffer_before_minutes INT NOT NULL,
    buffer_after_minutes INT NOT NULL,
    status VARCHAR(16) NOT NULL
);

CREATE TABLE connected_calendar (
    id UUID PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    created_at VARCHAR(64) NOT NULL
);

-- Seed a single default settings row and one in-memory calendar.
INSERT INTO settings (id, zone, granularity_minutes, minimum_notice_minutes)
VALUES (1, 'Europe/Paris', 30, 0);

INSERT INTO connected_calendar (id, name, provider, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default', 'IN_MEMORY', '2026-06-29T00:00:00Z');
```
Note: times stored as `HH:mm`/ISO strings keep the migration DB-agnostic and avoid driver time-mapping quirks; the repositories parse them to `java.time`.

- [ ] **Step 3: Implement `Tables.kt` and `Database.kt`**

`Tables.kt`:
```kotlin
package io.vladar107.data.persistence

import org.jetbrains.exposed.sql.Table

object SettingsTable : Table("settings") {
    val id = integer("id")
    val zone = varchar("zone", 64)
    val granularityMinutes = integer("granularity_minutes")
    val minimumNoticeMinutes = integer("minimum_notice_minutes")
    override val primaryKey = PrimaryKey(id)
}

object WorkingHoursTable : Table("working_hours") {
    val id = uuid("id")
    val dayOfWeek = varchar("day_of_week", 9)
    val startTime = varchar("start_time", 5)
    val endTime = varchar("end_time", 5)
    override val primaryKey = PrimaryKey(id)
}

object DateOverrideTable : Table("date_override") {
    val id = uuid("id")
    val date = varchar("override_date", 10)
    val startTime = varchar("start_time", 5).nullable()
    val endTime = varchar("end_time", 5).nullable()
    override val primaryKey = PrimaryKey(id)
}

object EventTypeTable : Table("event_type") {
    val id = uuid("id")
    val slug = varchar("slug", 128).uniqueIndex()
    val name = varchar("name", 256)
    val durationMinutes = integer("duration_minutes")
    val bufferBeforeMinutes = integer("buffer_before_minutes")
    val bufferAfterMinutes = integer("buffer_after_minutes")
    val status = varchar("status", 16)
    override val primaryKey = PrimaryKey(id)
}

object ConnectedCalendarTable : Table("connected_calendar") {
    val id = uuid("id")
    val name = varchar("name", 256)
    val provider = varchar("provider", 32)
    val createdAt = varchar("created_at", 64)
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

- [ ] **Step 4: Write the smoke test and verify the stack (adjust versions if needed)**

`DatabaseTest.kt`:
```kotlin
package io.vladar107.data.persistence

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseTest {
    @Test
    fun migratesAndSeedsConfig() {
        // Unique in-memory DB that stays alive for the connection.
        val url = "jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1"
        Db.init(url)
        transaction {
            assertEquals(1, SettingsTable.selectAll().count().toInt())
            assertEquals("Europe/Paris", SettingsTable.selectAll().single()[SettingsTable.zone])
            assertEquals(1, ConnectedCalendarTable.selectAll().count().toInt())
        }
    }
}
```
Run: `… gradlew -p … test --tests "io.vladar107.data.persistence.DatabaseTest"`
Expected: PASS. **If dependency resolution or compilation fails** (Exposed 1.0 module/package names, Flyway H2 module, H2 version), adjust the versions in `gradle.properties` and the imports to the working combination (try the latest Exposed 0.x with `exposed-java-time` if 1.0 fights you), re-run until green, and note the final versions in the report. `selectAll()`/`transaction` import paths may differ slightly across Exposed versions — fix imports to match the resolved version.

- [ ] **Step 5: Run the full build and commit**

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

Pure domain types for the booking world, the repository ports, and the function that merges Settings + EventType into the engine's `AvailabilityRules`.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/booking/EventType.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/booking/Settings.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/booking/ConnectedCalendar.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/booking/EffectiveRules.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/SettingsRepository.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/EventTypeRepository.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/ConnectedCalendarRepository.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/domain/booking/EffectiveRulesTest.kt`

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

- [ ] **Step 1: Write the failing test**

`EffectiveRulesTest.kt`:
```kotlin
package io.vladar107.domain.booking

import io.vladar107.domain.availability.DateOverride
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
    @Test
    fun mergesSettingsWithEventTypeDurationAndBuffers() {
        val settings = Settings(
            zone = ZoneId.of("Europe/Paris"),
            weekly = WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
            overrides = emptyList(),
            granularity = Duration.ofMinutes(30),
            minimumNotice = Duration.ofHours(2),
        )
        val eventType = EventType(
            id = UUID.randomUUID(), slug = "intro", name = "Intro",
            duration = Duration.ofMinutes(45),
            bufferBefore = Duration.ofMinutes(10), bufferAfter = Duration.ofMinutes(5),
            status = EventTypeStatus.ACTIVE,
        )
        val rules = eventType.effectiveRules(settings)
        assertEquals(settings.zone, rules.zone)
        assertEquals(settings.weekly, rules.weekly)
        assertEquals(settings.granularity, rules.granularity)
        assertEquals(settings.minimumNotice, rules.minimumNotice)
        assertEquals(Duration.ofMinutes(10), rules.bufferBefore) // from the event type
        assertEquals(Duration.ofMinutes(5), rules.bufferAfter)   // from the event type
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `… gradlew -p … test --tests "io.vladar107.domain.booking.EffectiveRulesTest"` → FAIL (unresolved).

- [ ] **Step 3: Implement the domain types and ports**

`EventType.kt`:
```kotlin
package io.vladar107.domain.booking

import java.time.Duration
import java.util.UUID

enum class EventTypeStatus { ACTIVE, INACTIVE }

data class EventType(
    val id: UUID,
    val slug: String,
    val name: String,
    val duration: Duration,
    val bufferBefore: Duration,
    val bufferAfter: Duration,
    val status: EventTypeStatus,
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
    val zone: ZoneId,
    val weekly: WeeklyAvailability,
    val overrides: List<DateOverride>,
    val granularity: Duration,
    val minimumNotice: Duration,
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
    zone = settings.zone,
    weekly = settings.weekly,
    overrides = settings.overrides,
    granularity = settings.granularity,
    bufferBefore = bufferBefore,
    bufferAfter = bufferAfter,
    minimumNotice = settings.minimumNotice,
)
```
`SettingsRepository.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.Settings

interface SettingsRepository {
    suspend fun load(): Settings
    suspend fun save(settings: Settings)
}
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

interface ConnectedCalendarRepository {
    suspend fun list(): List<ConnectedCalendar>
    suspend fun default(): ConnectedCalendar
}
```

- [ ] **Step 4: Run to verify pass, then commit**

Run: `… gradlew -p … test --tests "io.vladar107.domain.booking.EffectiveRulesTest"` → PASS. Then `… gradlew -p … build`.
```bash
git add time-matcher/src/main/kotlin/io/vladar107/domain/booking/ \
        time-matcher/src/main/kotlin/io/vladar107/application/booking/ \
        time-matcher/src/test/kotlin/io/vladar107/domain/booking/EffectiveRulesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add booking domain types, repository ports, and effective-rules assembly"
```

---

### Task 3: Calendar events + CalendarWriter port + in-memory adapter stores events

Generalize the calendar to store events (title + optional attendee), add a write port, and derive busy from stored events. A booked event therefore shows as busy.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/domain/availability/CalendarEvent.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/availability/CalendarWriter.kt`
- Modify: `time-matcher/src/main/kotlin/io/vladar107/data/repositories/InMemoryCalendarProvider.kt`
- Modify (test): `time-matcher/src/test/kotlin/io/vladar107/data/repositories/InMemoryCalendarProviderTest.kt`

**Interfaces:**
- Consumes: `TimeInterval`, `BusyInterval` (domain/availability); `CalendarProvider`, `CalendarBusyWriter` (existing ports).
- Produces:
  - `data class CalendarEvent(val interval: TimeInterval, val title: String, val attendeeName: String? = null, val attendeeEmail: String? = null)`
  - `interface CalendarWriter { suspend fun createEvent(calendarId: String, event: CalendarEvent) }`
  - `InMemoryCalendarProvider` now also implements `CalendarWriter`; stores `CalendarEvent`s; `addBusy` creates a titled "(busy)" event; `busyIntervals` derives from stored events.

- [ ] **Step 1: Add the failing test**

Append to `InMemoryCalendarProviderTest.kt` (keep the existing union test):
```kotlin
    @Test
    fun createdEventShowsAsBusy() = kotlinx.coroutines.runBlocking {
        val provider = InMemoryCalendarProvider()
        provider.createEvent(
            "work",
            io.vladar107.domain.availability.CalendarEvent(
                TimeInterval(java.time.Instant.parse("2030-01-07T10:00:00Z"), java.time.Instant.parse("2030-01-07T11:00:00Z")),
                title = "Intro with Sam",
                attendeeName = "Sam", attendeeEmail = "sam@example.com",
            ),
        )
        val window = TimeInterval(java.time.Instant.parse("2030-01-07T00:00:00Z"), java.time.Instant.parse("2030-01-07T23:59:59Z"))
        val busy = provider.busyIntervals(window)
        assertEquals(1, busy.size)
        assertEquals("work", busy.single().calendarId)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `… gradlew -p … test --tests "io.vladar107.data.repositories.InMemoryCalendarProviderTest"` → FAIL (no `createEvent`/`CalendarEvent`).

- [ ] **Step 3: Implement CalendarEvent + CalendarWriter**

`CalendarEvent.kt`:
```kotlin
package io.vladar107.domain.availability

/** An entry on a calendar. A booking is a CalendarEvent with an attendee; manual busy has none. */
data class CalendarEvent(
    val interval: TimeInterval,
    val title: String,
    val attendeeName: String? = null,
    val attendeeEmail: String? = null,
)
```
`CalendarWriter.kt`:
```kotlin
package io.vladar107.application.availability

import io.vladar107.domain.availability.CalendarEvent

/** Write port: create an event on a calendar (e.g. a booking). */
interface CalendarWriter {
    suspend fun createEvent(calendarId: String, event: CalendarEvent)
}
```

- [ ] **Step 4: Refactor `InMemoryCalendarProvider` to store events**

Replace the file body:
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

    override suspend fun addBusy(calendarId: String, interval: TimeInterval) {
        createEvent(calendarId, CalendarEvent(interval, title = "(busy)"))
    }

    override suspend fun createEvent(calendarId: String, event: CalendarEvent) {
        byCalendar.computeIfAbsent(calendarId) { CopyOnWriteArrayList() }.add(event)
    }

    override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> =
        byCalendar.flatMap { (calendarId, events) ->
            events.filter { it.interval.overlaps(window) }.map { BusyInterval(it.interval, calendarId) }
        }
}
```
(`CopyOnWriteArrayList` also closes the Task-8/Phase-1 Minor about unsynchronized writes.)

- [ ] **Step 5: Run tests to verify pass, then commit**

Run: `… gradlew -p … test --tests "io.vladar107.data.repositories.InMemoryCalendarProviderTest"` → PASS (both tests). Then `… gradlew -p … build`.
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

DB-backed adapters implementing the Task-2 ports, with H2 integration tests.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/data/repositories/ExposedSettingsRepository.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/data/repositories/ExposedEventTypeRepository.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/data/repositories/ExposedConnectedCalendarRepository.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/data/repositories/ExposedRepositoriesTest.kt`

**Interfaces:**
- Consumes: ports + domain from Task 2; `Db`, tables from Task 1; `WeeklyAvailability`, `DateOverride`, `LocalTimeRange` (domain/availability).
- Produces: `ExposedSettingsRepository`, `ExposedEventTypeRepository`, `ExposedConnectedCalendarRepository` (constructors take no args; they use the globally-connected Exposed `Database`).

- [ ] **Step 1: Write the failing integration test**

`ExposedRepositoriesTest.kt`:
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
        val seeded = repo.load() // from V1 seed
        assertEquals(ZoneId.of("Europe/Paris"), seeded.zone)

        val updated = Settings(
            zone = ZoneId.of("UTC"),
            weekly = WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
            overrides = listOf(DateOverride(LocalDate.parse("2030-12-25"), emptyList())),
            granularity = Duration.ofMinutes(15),
            minimumNotice = Duration.ofHours(1),
        )
        repo.save(updated)
        val reloaded = repo.load()
        assertEquals(ZoneId.of("UTC"), reloaded.zone)
        assertEquals(Duration.ofMinutes(15), reloaded.granularity)
        assertEquals(listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))), reloaded.weekly.rangesFor(DayOfWeek.MONDAY))
        assertEquals(1, reloaded.overrides.size)
    }

    @Test fun eventTypeCreateListFind() = runBlocking {
        val repo = ExposedEventTypeRepository()
        val et = EventType(UUID.randomUUID(), "intro", "Intro", Duration.ofMinutes(30), Duration.ZERO, Duration.ZERO, EventTypeStatus.ACTIVE)
        repo.create(et)
        assertEquals(1, repo.list().size)
        assertEquals("Intro", repo.findBySlug("intro")?.name)
        assertNull(repo.findBySlug("nope"))
    }

    @Test fun connectedCalendarHasSeededDefault() = runBlocking {
        val repo = ExposedConnectedCalendarRepository()
        assertEquals("IN_MEMORY", repo.default().provider)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `… gradlew -p … test --tests "io.vladar107.data.repositories.ExposedRepositoriesTest"` → FAIL (unresolved repositories).

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
            DateOverride(
                LocalDate.parse(it[DateOverrideTable.date]),
                if (s != null && e != null) listOf(LocalTimeRange(LocalTime.parse(s), LocalTime.parse(e))) else emptyList(),
            )
        }
        Settings(
            zone = ZoneId.of(row[SettingsTable.zone]),
            weekly = WeeklyAvailability(weekly),
            overrides = overrides,
            granularity = Duration.ofMinutes(row[SettingsTable.granularityMinutes].toLong()),
            minimumNotice = Duration.ofMinutes(row[SettingsTable.minimumNoticeMinutes].toLong()),
        )
    }

    override suspend fun save(settings: Settings): Unit = transaction {
        SettingsTable.update({ SettingsTable.id eq 1 }) {
            it[zone] = settings.zone.id
            it[granularityMinutes] = settings.granularity.toMinutes().toInt()
            it[minimumNoticeMinutes] = settings.minimumNotice.toMinutes().toInt()
        }
        WorkingHoursTable.deleteAll()
        settings.weekly.byDay.forEach { (day, ranges) ->
            ranges.forEach { r ->
                WorkingHoursTable.insert {
                    it[id] = UUID.randomUUID(); it[dayOfWeek] = day.name
                    it[startTime] = r.start.toString(); it[endTime] = r.end.toString()
                }
            }
        }
        DateOverrideTable.deleteAll()
        settings.overrides.forEach { o ->
            DateOverrideTable.insert {
                it[id] = UUID.randomUUID(); it[date] = o.date.toString()
                it[startTime] = o.ranges.firstOrNull()?.start?.toString()
                it[endTime] = o.ranges.firstOrNull()?.end?.toString()
            }
        }
    }
}
```
Note: `WeeklyAvailability` exposes `byDay: Map<DayOfWeek, List<LocalTimeRange>>` and `rangesFor(day)` (from Phase 1). An override row with null times = unavailable (empty ranges). (Multiple ranges per override date are out of scope here; the schema stores one range per override row — sufficient for 2a.)

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
        id = r[EventTypeTable.id], slug = r[EventTypeTable.slug], name = r[EventTypeTable.name],
        duration = Duration.ofMinutes(r[EventTypeTable.durationMinutes].toLong()),
        bufferBefore = Duration.ofMinutes(r[EventTypeTable.bufferBeforeMinutes].toLong()),
        bufferAfter = Duration.ofMinutes(r[EventTypeTable.bufferAfterMinutes].toLong()),
        status = EventTypeStatus.valueOf(r[EventTypeTable.status]),
    )

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
Note: `selectAll().where { }` / `singleOrNull()` API spelling may vary by Exposed version (resolved in Task 1) — match the working version's query DSL.

- [ ] **Step 4: Run to verify pass, then commit**

Run: `… gradlew -p … test --tests "io.vladar107.data.repositories.ExposedRepositoriesTest"` → PASS. Then `… gradlew -p … build`.
```bash
git add time-matcher/src/main/kotlin/io/vladar107/data/repositories/Exposed*.kt \
        time-matcher/src/test/kotlin/io/vladar107/data/repositories/ExposedRepositoriesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add Exposed-backed settings, event-type, connected-calendar repositories"
```

---

### Task 5: Settings use case + rewire the generic finder to Settings

Replace Phase 1's in-memory `AvailabilityConfigRepository` with the DB-backed `SettingsRepository`: a `SetSettingsCommand`, and the generic `FindAvailableSlotsQueryHandler` sourced from Settings (zero buffers + query duration). Retire the now-obsolete config types.

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/SetSettingsCommandHandler.kt`
- Modify: `time-matcher/src/main/kotlin/io/vladar107/application/availability/FindAvailableSlotsQueryHandler.kt`
- Delete: `application/availability/AvailabilityConfigRepository.kt`, `data/repositories/InMemoryAvailabilityConfigRepository.kt`, `application/availability/SetAvailabilityRulesCommandHandler.kt`
- Modify (test): `time-matcher/src/test/kotlin/io/vladar107/application/availability/FindAvailableSlotsQueryHandlerTest.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/application/booking/SetSettingsCommandTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository`, `Settings` (Task 2); `AvailabilityEngine`, `SlotSearch`, `AvailableSlots`, `TimeInterval`, `CalendarProvider` (existing); `Command`/`CommandHandler`/`Query`/`QueryHandler` infra.
- Produces:
  - `data class SetSettingsCommand(val settings: Settings) : Command<Unit>` + `SetSettingsCommandHandler(settingsRepository)`.
  - `FindAvailableSlotsQueryHandler(calendarProvider, settingsRepository, clock, engine = AvailabilityEngine())` — builds rules from Settings with `bufferBefore=ZERO, bufferAfter=ZERO`, duration from the query; returns `AvailableSlots(settings.zone, slots)` (unchanged result type from Phase 1).

- [ ] **Step 1: Write/adjust the failing tests**

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
            override suspend fun load() = error("unused")
            override suspend fun save(settings: Settings) { saved = settings }
        }
        val s = Settings(ZoneId.of("UTC"), WeeklyAvailability(emptyMap()), emptyList(), Duration.ofMinutes(30), Duration.ZERO)
        SetSettingsCommandHandler(repo).handle(SetSettingsCommand(s))
        assertEquals(s, saved)
    }
}
```
Rewrite `FindAvailableSlotsQueryHandlerTest.kt` to use a fake `SettingsRepository` instead of the deleted `AvailabilityConfigRepository`:
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
    private val settings = Settings(
        ZoneId.of("UTC"),
        WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
        emptyList(), Duration.ofMinutes(30), Duration.ZERO,
    )
    private val settingsRepo = object : SettingsRepository {
        override suspend fun load() = settings
        override suspend fun save(settings: Settings) = error("unused")
    }
    private val provider = object : CalendarProvider {
        override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> = emptyList()
    }

    @Test fun returnsSlotsInConfiguredZone() = runBlocking {
        val handler = FindAvailableSlotsQueryHandler(provider, settingsRepo, Clock.fixed(t("2020-01-01T00:00:00Z"), ZoneId.of("UTC")))
        val result = handler.handle(FindAvailableSlotsQuery(t("2030-01-07T00:00:00Z"), t("2030-01-07T23:59:59Z"), Duration.ofMinutes(60)))
        assertEquals(ZoneId.of("UTC"), result.zone)
        assertEquals(t("2030-01-07T09:00:00Z"), result.slots.first().start)
    }
}
```
(`AvailableSlots(zone, slots)` already exists from the Phase-1 zone-rendering fix.)

- [ ] **Step 2: Run to verify failure**

Run: `… gradlew -p … test --tests "io.vladar107.application.booking.SetSettingsCommandTest"` → FAIL.

- [ ] **Step 3: Implement `SetSettingsCommandHandler` and rewire the finder**

`SetSettingsCommandHandler.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.Settings
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler

data class SetSettingsCommand(val settings: Settings) : Command<Unit>

class SetSettingsCommandHandler(private val settingsRepository: SettingsRepository) :
    CommandHandler<Unit, SetSettingsCommand> {
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
Then **delete** `AvailabilityConfigRepository.kt`, `InMemoryAvailabilityConfigRepository.kt`, `SetAvailabilityRulesCommandHandler.kt`. (DI wiring for these is fixed in Task 8; the build will be red until then — that is expected for this task, so run only the focused unit tests here, not the full app.)

- [ ] **Step 4: Run the focused unit tests**

Run: `… gradlew -p … test --tests "io.vladar107.application.booking.SetSettingsCommandTest" --tests "io.vladar107.application.availability.FindAvailableSlotsQueryHandlerTest"` → PASS.
(Compilation of these test classes + their targets confirms the new shapes. The web/DI layer is updated in Task 8.)

- [ ] **Step 5: Commit**

```bash
git add -A time-matcher/src/main/kotlin/io/vladar107/application \
          time-matcher/src/main/kotlin/io/vladar107/data/repositories \
          time-matcher/src/test/kotlin/io/vladar107/application
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "refactor: source settings from DB-backed SettingsRepository; add SetSettingsCommand"
```

---

### Task 6: EventType use cases (create / list / get)

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/CreateEventTypeCommandHandler.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/ListEventTypesQueryHandler.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/GetEventTypeBySlugQueryHandler.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/application/booking/EventTypeUseCasesTest.kt`

**Interfaces:**
- Consumes: `EventTypeRepository`, `EventType`, `EventTypeStatus` (Task 2); infra Command/Query.
- Produces:
  - `data class CreateEventTypeCommand(val slug: String, val name: String, val duration: Duration, val bufferBefore: Duration, val bufferAfter: Duration) : Command<Unit>` + handler (assigns `UUID.randomUUID()`, status `ACTIVE`).
  - `class ListEventTypesQuery : Query<List<EventType>>` + handler.
  - `data class GetEventTypeBySlugQuery(val slug: String) : Query<EventType?>` + handler.

- [ ] **Step 1: Write the failing test**

`EventTypeUseCasesTest.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventTypeUseCasesTest {
    private class FakeRepo : EventTypeRepository {
        val items = mutableListOf<EventType>()
        override suspend fun create(eventType: EventType) { items += eventType }
        override suspend fun list() = items.toList()
        override suspend fun findBySlug(slug: String) = items.firstOrNull { it.slug == slug }
    }

    @Test fun createsActiveEventType() = runBlocking {
        val repo = FakeRepo()
        CreateEventTypeCommandHandler(repo).handle(
            CreateEventTypeCommand("intro", "Intro", Duration.ofMinutes(30), Duration.ofMinutes(5), Duration.ofMinutes(5)))
        val created = repo.list().single()
        assertEquals("intro", created.slug)
        assertEquals(io.vladar107.domain.booking.EventTypeStatus.ACTIVE, created.status)
        assertEquals(Duration.ofMinutes(30), created.duration)
        assertEquals("Intro", GetEventTypeBySlugQueryHandler(repo).handle(GetEventTypeBySlugQuery("intro"))?.name)
        assertNull(GetEventTypeBySlugQueryHandler(repo).handle(GetEventTypeBySlugQuery("none")))
        assertEquals(1, ListEventTypesQueryHandler(repo).handle(ListEventTypesQuery()).size)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `… --tests "io.vladar107.application.booking.EventTypeUseCasesTest"` → FAIL.

- [ ] **Step 3: Implement the handlers**

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
    val slug: String, val name: String,
    val duration: Duration, val bufferBefore: Duration, val bufferAfter: Duration,
) : Command<Unit>

class CreateEventTypeCommandHandler(private val repository: EventTypeRepository) :
    CommandHandler<Unit, CreateEventTypeCommand> {
    override suspend fun handle(command: CreateEventTypeCommand) {
        repository.create(
            EventType(UUID.randomUUID(), command.slug, command.name, command.duration,
                command.bufferBefore, command.bufferAfter, EventTypeStatus.ACTIVE))
    }
}
```
`ListEventTypesQueryHandler.kt`:
```kotlin
package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler

class ListEventTypesQuery : Query<List<EventType>>

class ListEventTypesQueryHandler(private val repository: EventTypeRepository) :
    QueryHandler<List<EventType>, ListEventTypesQuery> {
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

class GetEventTypeBySlugQueryHandler(private val repository: EventTypeRepository) :
    QueryHandler<EventType?, GetEventTypeBySlugQuery> {
    override suspend fun handle(query: GetEventTypeBySlugQuery): EventType? = repository.findBySlug(query.slug)
}
```

- [ ] **Step 4: Run to verify pass, then commit** (focused test passes; full build still red until Task 8)

```bash
git add time-matcher/src/main/kotlin/io/vladar107/application/booking/CreateEventTypeCommandHandler.kt \
        time-matcher/src/main/kotlin/io/vladar107/application/booking/ListEventTypesQueryHandler.kt \
        time-matcher/src/main/kotlin/io/vladar107/application/booking/GetEventTypeBySlugQueryHandler.kt \
        time-matcher/src/test/kotlin/io/vladar107/application/booking/EventTypeUseCasesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add event-type create/list/get use cases"
```

---

### Task 7: Booking use cases (per-type slots + book-with-double-booking-guard)

**Files:**
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/FindEventTypeSlotsQueryHandler.kt`
- Create: `time-matcher/src/main/kotlin/io/vladar107/application/booking/BookSlotCommandHandler.kt`
- Test: `time-matcher/src/test/kotlin/io/vladar107/application/booking/BookSlotCommandTest.kt`

**Interfaces:**
- Consumes: `EventTypeRepository`, `SettingsRepository`, `ConnectedCalendarRepository` (Task 2); `CalendarProvider`, `CalendarWriter`, `CalendarEvent`, `AvailableSlots`, `AvailabilityEngine`, `SlotSearch`, `TimeInterval` (availability); `effectiveRules` (Task 2).
- Produces:
  - `data class FindEventTypeSlotsQuery(val slug: String, val from: Instant, val to: Instant) : Query<AvailableSlots?>` + handler (null if missing/inactive).
  - `sealed interface BookingResult { data class Booked(val start: Instant, val end: Instant, val eventTypeName: String, val zone: ZoneId) : BookingResult; data object SlotTaken : BookingResult; data object EventTypeNotFound : BookingResult }`
  - `data class BookSlotCommand(val slug: String, val attendeeName: String, val attendeeEmail: String, val start: Instant) : Command<BookingResult>`
  - `class BookSlotCommandHandler(eventTypeRepository, settingsRepository, calendarProvider, calendarWriter, connectedCalendarRepository, clock, engine = AvailabilityEngine())` — bound as a **singleton** (holds a `Mutex`).

- [ ] **Step 1: Write the failing test**

`BookSlotCommandTest.kt`:
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
                store.filter { it.second.interval.overlaps(window) }.map { BusyInterval(it.second.interval, it.first) }
        }
        val writer = object : CalendarWriter {
            override suspend fun createEvent(calendarId: String, event: CalendarEvent) { store += calendarId to event; written += calendarId to event }
        }
        return BookSlotCommandHandler(
            eventTypeRepository = object : EventTypeRepository {
                override suspend fun create(eventType: EventType) {}
                override suspend fun list() = listOf(et)
                override suspend fun findBySlug(slug: String) = et.takeIf { it.slug == slug }
            },
            settingsRepository = object : SettingsRepository {
                override suspend fun load() = settings; override suspend fun save(settings: Settings) {}
            },
            calendarProvider = provider, calendarWriter = writer,
            connectedCalendarRepository = object : ConnectedCalendarRepository {
                override suspend fun list() = listOf(ConnectedCalendar(UUID.randomUUID(), "Default", "IN_MEMORY"))
                override suspend fun default() = ConnectedCalendar(UUID.randomUUID(), "Default", "IN_MEMORY")
            },
            clock = Clock.fixed(t("2020-01-01T00:00:00Z"), zone),
        )
    }

    @Test fun booksThenSecondBookingOfSameSlotIsTaken() = runBlocking {
        val written = CopyOnWriteArrayList<Pair<String, CalendarEvent>>()
        val h = handler(written)
        val first = h.handle(BookSlotCommand("intro", "Sam", "sam@example.com", t("2030-01-07T09:00:00Z")))
        assertTrue(first is BookingResult.Booked)
        assertEquals(1, written.size)
        val second = h.handle(BookSlotCommand("intro", "Pat", "pat@example.com", t("2030-01-07T09:00:00Z")))
        assertTrue(second is BookingResult.SlotTaken)
        assertEquals(1, written.size) // no second write
    }

    @Test fun unknownSlugIsNotFound() = runBlocking {
        val h = handler(CopyOnWriteArrayList())
        assertTrue(h.handle(BookSlotCommand("nope", "Sam", "s@e.com", t("2030-01-07T09:00:00Z"))) is BookingResult.EventTypeNotFound)
    }
}
```

- [ ] **Step 2: Run to verify it fails** — FAIL (unresolved).

- [ ] **Step 3: Implement the handlers**

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
        val rules = et.effectiveRules(settings)
        val busy = calendarProvider.busyIntervals(TimeInterval(query.from, query.to))
        val slots = engine.findSlots(rules, busy, SlotSearch(query.from, query.to, et.duration), clock.instant())
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

data class BookSlotCommand(
    val slug: String, val attendeeName: String, val attendeeEmail: String, val start: Instant,
) : Command<BookingResult>

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
            // Fetch busy slightly wider than the slot so buffer-overlapping events are considered.
            val window = TimeInterval(command.start.minus(et.bufferBefore), end.plus(et.bufferAfter))
            val busy = calendarProvider.busyIntervals(window)
            val open = engine.findSlots(rules, busy, SlotSearch(command.start, end, et.duration), clock.instant())
            if (open.none { it.start == command.start }) {
                BookingResult.SlotTaken
            } else {
                val calendarId = connectedCalendarRepository.default().id.toString()
                calendarWriter.createEvent(
                    calendarId,
                    CalendarEvent(TimeInterval(command.start, end), "${et.name} with ${command.attendeeName}", command.attendeeName, command.attendeeEmail),
                )
                BookingResult.Booked(command.start, end, et.name, settings.zone)
            }
        }
    }
}
```
Note: `kotlinx.coroutines.sync.Mutex` is available via Ktor's transitive coroutines (same as `runBlocking`). The mutex makes validate-then-write atomic, so the second booking sees the first as busy.

- [ ] **Step 4: Run to verify pass, then commit** (focused tests pass; full build still red until Task 8)

```bash
git add time-matcher/src/main/kotlin/io/vladar107/application/booking/FindEventTypeSlotsQueryHandler.kt \
        time-matcher/src/main/kotlin/io/vladar107/application/booking/BookSlotCommandHandler.kt \
        time-matcher/src/test/kotlin/io/vladar107/application/booking/BookSlotCommandTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: add per-event-type slot finder and book-slot command with double-booking guard"
```

---

### Task 8: Web layer + DI wiring (settings + event types) — restores a green full build

DTOs and controllers for host config/event-types, plus all DI wiring (DB init, Exposed repos, CalendarWriter, new handlers; remove the deleted Phase-1 config bindings). This is the task that makes the whole app compile and boot again.

**Files:**
- Create: `web/booking/dto/EventTypeDto.kt`, `web/booking/EventTypeController.kt`
- Modify: `web/availability/dto/AvailabilityConfigRequest.kt` (drop buffer fields; build a `Settings`), `web/availability/AvailabilityController.kt` (persist Settings via `SetSettingsCommand`)
- Modify: `web/di/ConfigureRepositories.kt`, `ConfigureCommands.kt`, `ConfigureQueries.kt`, `ConfigureExternalServices.kt`, `web/di/ConfigureDi.kt` (DB init), `Application.kt` (register `configureEventTypes`)
- Test: `time-matcher/src/test/kotlin/io/vladar107/web/booking/EventTypeRoutesTest.kt`

**Interfaces:**
- Consumes: all Task 2–7 handlers/ports/adapters; `Db` (Task 1); `CommandProvider`/`QueryProvider`.
- Produces: `fun Application.configureEventTypes()`; `AvailabilityConfigRequest.toSettings(): Settings`.

- [ ] **Step 1: Write the failing integration test**

`EventTypeRoutesTest.kt`:
```kotlin
package io.vladar107.web.booking

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.vladar107.module
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventTypeRoutesTest {
    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test fun createsAndListsEventTypes() = testApplication {
        application { module() }
        val client = jsonClient()
        val create = client.post("/event-types") {
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"intro","name":"Intro call","durationMinutes":30,"bufferBeforeMinutes":0,"bufferAfterMinutes":0}""")
        }
        assertEquals(HttpStatusCode.Created, create.status)
        val list = client.get("/event-types")
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("intro"))
        assertEquals(HttpStatusCode.OK, client.get("/event-types/intro").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/event-types/missing").status)
    }
}
```
Note: tests must use an H2 DB. Drive the runtime DB URL from config so tests use a unique in-memory URL — see Step 4 (the app reads `db.url` from `application.yaml`, overridable per test via an env/system property, or the DI uses an in-memory URL when a test flag is set). Simplest: `application.yaml` sets `db.url: "jdbc:h2:mem:timematcher;DB_CLOSE_DELAY=-1"` for now (file mode is a follow-up once booking works), so tests and runtime share the in-memory config DB. Record this choice.

- [ ] **Step 2: Run to verify it fails** — FAIL (routes/DTO/DI unresolved or red build from Task 5/6/7 deletions).

- [ ] **Step 3: Update the config DTO + controller**

Rewrite `AvailabilityConfigRequest.kt` to drop buffers and build a `Settings` (reuse the existing `LocalTimeRangeDto`/`DateOverrideDto`):
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

@Serializable
data class LocalTimeRangeDto(val start: String, val end: String)

@Serializable
data class DateOverrideDto(val date: String, val ranges: List<LocalTimeRangeDto>)

@Serializable
data class AvailabilityConfigRequest(
    val zone: String,
    val granularityMinutes: Long,
    val minimumNoticeMinutes: Long = 0,
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
            granularity = Duration.ofMinutes(granularityMinutes),
            minimumNotice = Duration.ofMinutes(minimumNoticeMinutes),
        )
    }
}
```
In `AvailabilityController.kt`, change the `PUT /availability/config` handler to `commandProvider.run(SetSettingsCommand(call.receive<AvailabilityConfigRequest>().toSettings()))` inside the existing try/catch→400, responding `204`. Update imports (drop `SetAvailabilityRulesCommand`, add `SetSettingsCommand` + `AvailabilityConfigRequest`). Leave `GET /availability/slots` and `POST /calendars/{id}/busy` as-is (the query result `AvailableSlots` is unchanged; mapping to `SlotDto` stays).

- [ ] **Step 4: EventType DTO + controller + DB-aware DI**

`EventTypeDto.kt`:
```kotlin
package io.vladar107.web.booking.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateEventTypeRequest(
    val slug: String, val name: String,
    val durationMinutes: Long, val bufferBeforeMinutes: Long = 0, val bufferAfterMinutes: Long = 0,
)

@Serializable
data class EventTypeDto(
    val id: String, val slug: String, val name: String,
    val durationMinutes: Long, val bufferBeforeMinutes: Long, val bufferAfterMinutes: Long, val status: String,
)
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

private fun EventType.toDto() = EventTypeDto(
    id.toString(), slug, name, duration.toMinutes(), bufferBefore.toMinutes(), bufferAfter.toMinutes(), status.name)

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
                    CreateEventTypeCommand(b.slug, b.name, Duration.ofMinutes(b.durationMinutes),
                        Duration.ofMinutes(b.bufferBeforeMinutes), Duration.ofMinutes(b.bufferAfterMinutes))
                } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid event type: ${e.message}") }
                commandProvider.run(cmd)
                call.respond(HttpStatusCode.Created)
            }
            get {
                val list: List<EventType> = queryProvider.query(ListEventTypesQuery())
                call.respond(list.map { it.toDto() })
            }
            get("/{slug}") {
                val slug = call.parameters["slug"]!!
                val et: EventType? = queryProvider.query(GetEventTypeBySlugQuery(slug))
                if (et == null) call.respond(HttpStatusCode.NotFound, "Unknown event type") else call.respond(et.toDto())
            }
        }
    }
}
```
DI changes:
- `ConfigureExternalServices.kt`: add the DB init + bind a `Clock` (keep). Read the JDBC URL from config:
```kotlin
fun DI.MainBuilder.configureExternalServices(application: Application) {
    val url = application.environment.config.propertyOrNull("db.url")?.getString() ?: "jdbc:h2:mem:timematcher;DB_CLOSE_DELAY=-1"
    io.vladar107.data.persistence.Db.init(url)
    bind<java.time.Clock>() with singleton { java.time.Clock.systemDefaultZone() }
}
```
(Adjust `configureExternalServices()` call site in `ConfigureDi.kt` to pass `this@configureDi`.) Add `db: { url: "jdbc:h2:mem:timematcher;DB_CLOSE_DELAY=-1" }` to `application.yaml`. (File mode is a follow-up.)
- `ConfigureRepositories.kt`: replace the deleted `AvailabilityConfigRepository` binding with the new repos (singletons):
```kotlin
    bind<InMemoryCalendarProvider>() with singleton { InMemoryCalendarProvider() }
    bind<CalendarProvider>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<CalendarBusyWriter>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<CalendarWriter>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<SettingsRepository>() with singleton { ExposedSettingsRepository() }
    bind<EventTypeRepository>() with singleton { ExposedEventTypeRepository() }
    bind<ConnectedCalendarRepository>() with singleton { ExposedConnectedCalendarRepository() }
    // keep the existing UserCreationRepository binding
```
- `ConfigureQueries.kt`: bind `QueryHandler<AvailableSlots, FindAvailableSlotsQuery>` (now needs `instance()` settingsRepo), `QueryHandler<List<EventType>, ListEventTypesQuery>`, `QueryHandler<EventType?, GetEventTypeBySlugQuery>`, `QueryHandler<AvailableSlots?, FindEventTypeSlotsQuery>`.
- `ConfigureCommands.kt`: keep user command; add `CommandHandler<Unit, SetSettingsCommand>`, `CommandHandler<Unit, CreateEventTypeCommand>` (provider), and `CommandHandler<BookingResult, BookSlotCommand>` with **singleton** (holds the mutex):
```kotlin
    bind<CommandHandler<BookingResult, BookSlotCommand>>() with singleton {
        BookSlotCommandHandler(instance(), instance(), instance(), instance(), instance(), instance())
    }
```
- `Application.kt`: add `configureEventTypes()` (and `configureBooking()` from Task 9) to `module()`.

- [ ] **Step 5: Run the build + the event-type test**

Run: `… gradlew -p … build` → BUILD SUCCESSFUL; then `… --tests "io.vladar107.web.booking.EventTypeRoutesTest"` → PASS, and confirm the prior suites still pass.

- [ ] **Step 6: Commit**

```bash
git add time-matcher/src/main/kotlin/io/vladar107/web time-matcher/src/main/kotlin/io/vladar107/Application.kt \
        time-matcher/src/main/resources/application.yaml \
        time-matcher/src/test/kotlin/io/vladar107/web/booking/EventTypeRoutesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: wire DB-backed config + event-type endpoints; restore green build"
```

---

### Task 9: Web layer — public booking endpoints + integration tests

**Files:**
- Create: `web/booking/dto/BookingDto.kt`, `web/booking/BookingController.kt`
- Modify: `Application.kt` (register `configureBooking`), `web/di/ConfigureQueries.kt` (if not already bound in Task 8)
- Test: `time-matcher/src/test/kotlin/io/vladar107/web/booking/BookingRoutesTest.kt`

**Interfaces:**
- Consumes: `FindEventTypeSlotsQuery`, `BookSlotCommand`, `BookingResult` (Task 7); `AvailableSlots`; `SlotDto` (existing).
- Produces: `fun Application.configureBooking()`.

- [ ] **Step 1: Write the failing integration test**

`BookingRoutesTest.kt`:
```kotlin
package io.vladar107.web.booking

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

class BookingRoutesTest {
    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test fun bookingRemovesTheSlotAndSecondBookingConflicts() = testApplication {
        application { module() }
        val client = jsonClient()
        // configure UTC Mon 09-17
        client.put("/availability/config") { contentType(ContentType.Application.Json)
            setBody("""{"zone":"UTC","granularityMinutes":60,"weekly":{"MONDAY":[{"start":"09:00","end":"17:00"}]},"overrides":[]}""") }
        client.post("/event-types") { contentType(ContentType.Application.Json)
            setBody("""{"slug":"intro","name":"Intro","durationMinutes":60}""") }

        val before = Json.decodeFromString<List<SlotDto>>(
            client.get("/book/intro/slots") { parameter("from", "2030-01-07T00:00:00Z"); parameter("to", "2030-01-07T23:59:59Z") }.bodyAsText())
        assertTrue(before.any { it.start == "2030-01-07T09:00:00Z" })

        val booked = client.post("/book/intro") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"Sam","attendeeEmail":"sam@example.com","start":"2030-01-07T09:00:00Z"}""") }
        assertEquals(HttpStatusCode.Created, booked.status)

        val after = Json.decodeFromString<List<SlotDto>>(
            client.get("/book/intro/slots") { parameter("from", "2030-01-07T00:00:00Z"); parameter("to", "2030-01-07T23:59:59Z") }.bodyAsText())
        assertTrue(after.none { it.start == "2030-01-07T09:00:00Z" })

        val conflict = client.post("/book/intro") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"Pat","attendeeEmail":"pat@example.com","start":"2030-01-07T09:00:00Z"}""") }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
    }

    @Test fun bookingUnknownEventTypeIs404() = testApplication {
        application { module() }
        val r = jsonClient().post("/book/nope") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"X","attendeeEmail":"x@e.com","start":"2030-01-07T09:00:00Z"}""") }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }
}
```
Note: `module()` boots one app/DB per `testApplication`; the in-memory H2 (`DB_CLOSE_DELAY=-1`) persists for that app's lifetime. Because the DB is process-global in-memory, ensure event-type slugs differ across tests or the schema is fresh — if cross-test bleed appears, give each test a unique slug (e.g. include a per-test suffix) and assert accordingly.

- [ ] **Step 2: Run to verify it fails** — FAIL (booking routes unresolved / 404).

- [ ] **Step 3: Implement booking DTO + controller**

`BookingDto.kt`:
```kotlin
package io.vladar107.web.booking.dto

import kotlinx.serialization.Serializable

@Serializable
data class BookRequest(val attendeeName: String, val attendeeEmail: String, val start: String)

@Serializable
data class BookingConfirmation(val start: String, val end: String, val eventType: String)
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
                val from = call.request.queryParameters["from"]; val to = call.request.queryParameters["to"]
                val query = try {
                    val f = Instant.parse(from); val t = Instant.parse(to)
                    require(f.isBefore(t)) { "from must be before to" }
                    FindEventTypeSlotsQuery(slug, f, t)
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
Register `configureBooking()` in `Application.module()` after `configureEventTypes()`.

- [ ] **Step 4: Run build + booking test, then commit**

Run: `… gradlew -p … build` and `… --tests "io.vladar107.web.booking.BookingRoutesTest"` → PASS.
```bash
git add time-matcher/src/main/kotlin/io/vladar107/web/booking time-matcher/src/main/kotlin/io/vladar107/Application.kt \
        time-matcher/src/test/kotlin/io/vladar107/web/booking/BookingRoutesTest.kt
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "feat: public booking endpoints (per-type slots + book with 201/409/404)"
```

---

### Task 10: OpenAPI + ADR + diagram + final build

**Files:**
- Modify: `time-matcher/src/main/resources/openapi/documentation.yaml` and `time-matcher/resources/openapi/documentation.yaml` (add event-type + booking endpoints)
- Create: `documentation/adr/20260629-bookings-live-in-the-calendar.md`
- Modify: `documentation/diagrams/Container.md` (Phase-2 note)

- [ ] **Step 1: Document the new endpoints in both OpenAPI files**

Add paths `POST /event-types`, `GET /event-types`, `GET /event-types/{slug}`, `GET /book/{slug}/slots`, `POST /book/{slug}` and schemas `CreateEventTypeRequest`, `EventTypeDto`, `BookRequest`, `BookingConfirmation`, mirroring the controllers' shapes (ISO-8601 strings; durations in minutes). Keep the existing availability/user paths. Update both files identically (as in Phase 1).

- [ ] **Step 2: Write the ADR**

`documentation/adr/20260629-bookings-live-in-the-calendar.md`:
```markdown
# Bookings live in the calendar; the DB holds only configuration

- Status: accepted
- Deciders: vladar107
- Date: 2026-06-29
- Tags: architecture, booking, persistence

## Context and Problem Statement

Phase 2 introduces booking. We must decide where bookings and configuration live.

## Decision Outcome

The **calendar is the system of record for bookings** — a booking is an event written to a connected calendar (via the `CalendarWriter` port; the in-memory calendar adapter in slice 2a, real Google in 2b), and availability is pulled live from calendars. The **database (H2, via Exposed + Flyway) holds only configuration**: settings, event types, connected-calendar records. **Booking logic is in-memory/stateless**: compute open slots, validate under a lock, then write the event.

Consequences:
- The Phase-1 `AvailabilityEngine` is unchanged; its `AvailabilityRules` is assembled from host-global `Settings` + a per-`EventType` duration/buffers.
- Double-booking is prevented by serializing validate-then-write with a Mutex (sufficient for a single-process, single-host slice).
- Roles: one host manages settings/event types; anyone with an event type's `slug` (the link) can book. Host authentication is deferred to slice 2c.
- Slice 2a uses an in-memory H2 config DB; switching to H2 file mode (durable config) is a small follow-up via `db.url`.
```

- [ ] **Step 3: Diagram note + final build + commit**

Add to `documentation/diagrams/Container.md` (under the Phase-1 note):
```markdown
> Phase 2a (implemented): EventTypes + booking. Config (settings, event types, connected calendars) in H2; bookings written to the calendar via the CalendarWriter port. Real Google calendar + host auth are later slices.
```
Run: `… gradlew -p … build` → BUILD SUCCESSFUL (all suites green).
```bash
git add time-matcher/src/main/resources/openapi/documentation.yaml time-matcher/resources/openapi/documentation.yaml \
        documentation/adr/20260629-bookings-live-in-the-calendar.md documentation/diagrams/Container.md
git -c user.email=vladar107@gmail.com commit --author="Vladislav Ramazaev <vladar107@gmail.com>" \
  -m "docs: OpenAPI for event-types/booking; ADR + diagram for Phase 2a"
```

---

## Self-Review

**Spec coverage:**
- Config/settings split (buffers→EventType) → Task 2 (`effectiveRules`), Task 5 (finder), Task 8 (config DTO).
- H2 schema (settings/working_hours/date_override/event_type/connected_calendar, seeded) → Task 1.
- Persistence stack (H2 file + Flyway + Exposed) + version de-risk → Task 1. (Runtime URL is in-memory in 2a; file mode noted as a follow-up — a deliberate, logged scope cut.)
- `CalendarWriter` + in-memory calendar stores events (booking → busy) → Task 3.
- DB-backed repos → Task 4. EventType use cases → Task 6. Settings use case + finder rewire → Task 5.
- Booking flow (GET per-type slots, POST book, 201/409/404, double-booking guard) → Tasks 7, 9.
- Host vs public routes; auth deferred to 2c → Tasks 8, 9 (+ ADR). EventTypes create/list/get only → Task 6/8.
- Engine unchanged → confirmed (Task 2 assembles its input; no engine edits anywhere).
- Tests integration-weighted + focused units → Tasks 4,8,9 (integration), 2,6,7 (units).
- Docs (OpenAPI, ADR, diagram) → Task 10.

**Placeholder scan:** No TBD/TODO; each code step has complete code. Two explicit, logged scope decisions (in-memory H2 URL in 2a → file mode follow-up; single range per override row) — not placeholders.

**Type consistency:** `Settings(zone, weekly, overrides, granularity, minimumNotice)` consistent across Tasks 2/4/5/8. `EventType(id, slug, name, duration, bufferBefore, bufferAfter, status)` consistent across 2/4/6/7. `effectiveRules(settings)` 2→7. `CalendarWriter.createEvent(calendarId, CalendarEvent)` 3→7. `AvailableSlots(zone, slots)` (Phase-1) reused 5/7/9. `BookingResult` (Booked/SlotTaken/EventTypeNotFound) 7→9. Repository port signatures identical across ports (2), adapters (4), and consumers (5/6/7).

**Known cross-version risk (flagged, not a gap):** Exposed 1.0 module/package/query-DSL names and the Flyway H2 module are resolved empirically in Task 1; later tasks' Exposed imports/query spellings follow whatever Task 1 pins.
