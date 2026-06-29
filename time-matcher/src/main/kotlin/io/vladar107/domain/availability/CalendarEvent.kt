package io.vladar107.domain.availability

/** An entry on a calendar. A booking is a CalendarEvent with an attendee; manual busy has none. */
data class CalendarEvent(
    val interval: TimeInterval, val title: String,
    val attendeeName: String? = null, val attendeeEmail: String? = null,
)
