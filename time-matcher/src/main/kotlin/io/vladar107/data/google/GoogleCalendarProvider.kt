package io.vladar107.data.google

import io.vladar107.application.availability.CalendarException
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
            val rt = cal.refreshToken ?: throw CalendarException("Connected calendar ${cal.id} is missing a refresh token")
            val calId = cal.externalCalendarId ?: throw CalendarException("Connected calendar ${cal.id} is missing a calendar id")
            val token = tokens.accessToken(rt)
            api.freeBusy(token, calId, window).map { BusyInterval(it, cal.id.toString()) }
        }
}
