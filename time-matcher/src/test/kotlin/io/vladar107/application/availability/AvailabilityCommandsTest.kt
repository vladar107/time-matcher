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
