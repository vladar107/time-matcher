package io.vladar107.application.availability

/** No is_booking_target calendar is connected. Mapped to HTTP 503 at the web boundary. */
class NoBookingCalendarException : RuntimeException("No booking calendar connected")
