package io.vladar107.data.google

import io.vladar107.application.availability.CalendarWriter
import io.vladar107.domain.availability.CalendarEvent

/** Writes bookings to the single configured Google calendar (ignores the per-call id in this single-calendar slice). */
class GoogleCalendarWriter(private val api: GoogleCalendarApi, private val calendarId: String) : CalendarWriter {
    override suspend fun createEvent(calendarId: String, event: CalendarEvent) = api.insertEvent(this.calendarId, event)
}
