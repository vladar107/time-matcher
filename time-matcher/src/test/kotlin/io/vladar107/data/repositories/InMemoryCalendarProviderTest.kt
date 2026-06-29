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
}
