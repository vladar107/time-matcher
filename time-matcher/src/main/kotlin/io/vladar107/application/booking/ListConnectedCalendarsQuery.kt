package io.vladar107.application.booking

import io.vladar107.domain.booking.ConnectedCalendar
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler

class ListConnectedCalendarsQuery : Query<List<ConnectedCalendar>>

class ListConnectedCalendarsQueryHandler(private val repo: ConnectedCalendarRepository) : QueryHandler<List<ConnectedCalendar>, ListConnectedCalendarsQuery> {
    override suspend fun handle(query: ListConnectedCalendarsQuery): List<ConnectedCalendar> = repo.googleCalendars()
}
