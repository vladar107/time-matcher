package io.vladar107.web.booking.dto

import kotlinx.serialization.Serializable

@Serializable data class BookRequest(val attendeeName: String, val attendeeEmail: String, val start: String)
@Serializable data class BookingConfirmation(val start: String, val end: String, val eventType: String)
