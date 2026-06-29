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
