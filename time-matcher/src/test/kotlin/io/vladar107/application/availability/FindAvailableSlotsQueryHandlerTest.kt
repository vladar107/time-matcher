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
import kotlin.test.assertNotNull
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
        val result = handler.handle(
            FindAvailableSlotsQuery(
                from = t("2030-01-07T00:00:00Z"),
                to = t("2030-01-07T23:59:59Z"),
                duration = Duration.ofMinutes(60),
            )
        )
        assertTrue(result.slots.isNotEmpty())
        assertEquals(t("2030-01-07T09:00:00Z"), result.slots.first().start)
        assertEquals(ZoneId.of("UTC"), result.zone)
    }
}
