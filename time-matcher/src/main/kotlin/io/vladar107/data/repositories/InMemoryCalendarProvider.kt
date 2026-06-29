package io.vladar107.data.repositories

import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.TimeInterval
import java.util.concurrent.ConcurrentHashMap

/** In-memory busy store, aggregating across calendars. Bind as a Kodein singleton. */
class InMemoryCalendarProvider : CalendarProvider, CalendarBusyWriter {
    private val byCalendar = ConcurrentHashMap<String, MutableList<TimeInterval>>()

    override suspend fun addBusy(calendarId: String, interval: TimeInterval) {
        byCalendar.computeIfAbsent(calendarId) { mutableListOf() }.add(interval)
    }

    override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> =
        byCalendar.flatMap { (calendarId, intervals) ->
            intervals.filter { it.overlaps(window) }.map { BusyInterval(it, calendarId) }
        }
}
