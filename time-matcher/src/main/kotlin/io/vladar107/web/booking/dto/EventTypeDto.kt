package io.vladar107.web.booking.dto

import kotlinx.serialization.Serializable

@Serializable data class CreateEventTypeRequest(
    val slug: String, val name: String, val durationMinutes: Long, val bufferBeforeMinutes: Long = 0, val bufferAfterMinutes: Long = 0)
@Serializable data class EventTypeDto(
    val id: String, val slug: String, val name: String,
    val durationMinutes: Long, val bufferBeforeMinutes: Long, val bufferAfterMinutes: Long, val status: String)
