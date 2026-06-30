package io.vladar107.data.repositories

import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.domain.availability.TimeInterval

/** Used in google mode: manual busy-seeding is ignored (real busy comes from Google). */
class NoOpCalendarBusyWriter : CalendarBusyWriter {
    override suspend fun addBusy(calendarId: String, interval: TimeInterval) { /* no-op */ }
}
