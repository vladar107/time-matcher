package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler

class ListEventTypesQuery : Query<List<EventType>>
class ListEventTypesQueryHandler(private val repository: EventTypeRepository) : QueryHandler<List<EventType>, ListEventTypesQuery> {
    override suspend fun handle(query: ListEventTypesQuery): List<EventType> = repository.list()
}
