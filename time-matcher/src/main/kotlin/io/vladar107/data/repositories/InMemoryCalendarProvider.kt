package io.vladar107.data.repositories

import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.TimeInterval
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory calendar store. Holds events per calendarId; busy is derived. Bind as a Kodein singleton. */
class InMemoryCalendarProvider : CalendarProvider, CalendarBusyWriter, CalendarWriter {
    private val byCalendar = ConcurrentHashMap<String, CopyOnWriteArrayList<CalendarEvent>>()

    override suspend fun addBusy(calendarId: String, interval: TimeInterval) =
        createEvent(calendarId, CalendarEvent(interval, title = "(busy)"))

    override suspend fun createEvent(calendarId: String, event: CalendarEvent) {
        byCalendar.computeIfAbsent(calendarId) { CopyOnWriteArrayList() }.add(event)
    }

    override suspend fun busyIntervals(window: TimeInterval): List<BusyInterval> =
        byCalendar.flatMap { (calendarId, events) ->
            events.filter { it.interval.overlaps(window) }.map { BusyInterval(it.interval, calendarId) }
        }
}
