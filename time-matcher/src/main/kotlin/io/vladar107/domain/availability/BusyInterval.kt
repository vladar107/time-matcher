package io.vladar107.domain.availability

/** A busy block sourced from a specific calendar. */
data class BusyInterval(val interval: TimeInterval, val calendarId: String)
