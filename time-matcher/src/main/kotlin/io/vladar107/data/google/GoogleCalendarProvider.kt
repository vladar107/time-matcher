package io.vladar107.data.google

import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.booking.ConnectedCalendarRepository
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.TimeInterval

/** Busy = union of free/busy across every connected Google calendar. */
class GoogleCalendarProvider(
    private val repo: ConnectedCalendarRepository,
    private val tokens: GoogleTokenManager,
    private val api: GoogleCalendarApi,
) : CalendarProvider {
    override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> =
        repo.googleCalendars().flatMap { cal ->
            val token = tokens.accessToken(cal.refreshToken!!)
            api.freeBusy(token, cal.externalCalendarId!!, window).map { BusyInterval(it, cal.id.toString()) }
        }
}
