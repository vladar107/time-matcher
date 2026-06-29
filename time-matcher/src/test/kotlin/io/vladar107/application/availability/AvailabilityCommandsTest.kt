package io.vladar107.application.availability

import io.vladar107.domain.availability.TimeInterval
import kotlinx.coroutines.runBlocking
import java.time.Instant
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
}
