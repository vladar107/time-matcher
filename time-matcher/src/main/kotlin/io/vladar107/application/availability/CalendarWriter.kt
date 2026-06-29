package io.vladar107.application.availability

import io.vladar107.domain.availability.CalendarEvent

interface CalendarWriter { suspend fun createEvent(calendarId: String, event: CalendarEvent) }
