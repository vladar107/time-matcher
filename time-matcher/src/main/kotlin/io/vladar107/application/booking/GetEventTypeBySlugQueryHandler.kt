package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler

data class GetEventTypeBySlugQuery(val slug: String) : Query<EventType?>
class GetEventTypeBySlugQueryHandler(private val repository: EventTypeRepository) : QueryHandler<EventType?, GetEventTypeBySlugQuery> {
    override suspend fun handle(query: GetEventTypeBySlugQuery): EventType? = repository.findBySlug(query.slug)
}
