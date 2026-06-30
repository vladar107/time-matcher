package io.vladar107.data.google

import io.vladar107.application.availability.CalendarException
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.application.availability.NoBookingCalendarException
import io.vladar107.application.booking.ConnectedCalendarRepository
import io.vladar107.domain.availability.CalendarEvent

/** Writes bookings to the single is_booking_target calendar (ignores the per-call id). */
class GoogleCalendarWriter(
    private val repo: ConnectedCalendarRepository,
    private val tokens: GoogleTokenManager,
    private val api: GoogleCalendarApi,
) : CalendarWriter {
    override suspend fun createEvent(calendarId: String, event: CalendarEvent) {
        val target = repo.bookingTarget() ?: throw NoBookingCalendarException()
        val rt = target.refreshToken ?: throw CalendarException("Connected calendar ${target.id} is missing a refresh token")
        val calId = target.externalCalendarId ?: throw CalendarException("Connected calendar ${target.id} is missing a calendar id")
        api.insertEvent(tokens.accessToken(rt), calId, event)
    }
}
