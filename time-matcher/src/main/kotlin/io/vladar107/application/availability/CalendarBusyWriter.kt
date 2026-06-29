package io.vladar107.application.availability

import io.vladar107.domain.availability.TimeInterval

/** Write port: seed a calendar's busy blocks (Phase 1 demo source). */
interface CalendarBusyWriter {
    suspend fun addBusy(calendarId: String, interval: TimeInterval)
}
