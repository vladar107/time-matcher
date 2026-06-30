package io.vladar107.data.google

import io.vladar107.application.availability.CalendarProvider
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.TimeInterval

/** Reads free/busy from the single configured Google calendar. */
class GoogleCalendarProvider(private val api: GoogleCalendarApi, private val calendarId: String) : CalendarProvider {
    override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> =
        api.freeBusy(calendarId, window).map { BusyInterval(it, calendarId) }
}
