package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType

interface EventTypeRepository {
    suspend fun create(eventType: EventType)
    suspend fun list(): List<EventType>
    suspend fun findBySlug(slug: String): EventType?
}
