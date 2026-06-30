package io.vladar107.data.google

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
        api.insertEvent(tokens.accessToken(target.refreshToken!!), target.externalCalendarId!!, event)
    }
}
