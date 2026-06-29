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
        emptyList(), Duration.ofMinutes(30), Duration.ZERO
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
