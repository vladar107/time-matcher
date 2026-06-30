package io.vladar107.application.availability

/** A calendar backend (e.g. Google) failed. Mapped to HTTP 502 at the web boundary. */
class CalendarException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
