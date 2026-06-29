package io.vladar107.application.availability

import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.TimeInterval

/** Read port: busy intervals across all connected calendars, within a window. */
interface CalendarProvider {
    suspend fun busyIntervals(window: TimeInterval): List<BusyInterval>
}
