package io.vladar107.web.di

import io.vladar107.application.availability.AvailableSlots
import io.vladar107.application.availability.FindAvailableSlotsQuery
import io.vladar107.application.availability.FindAvailableSlotsQueryHandler
import io.vladar107.application.booking.FindEventTypeSlotsQuery
import io.vladar107.application.booking.FindEventTypeSlotsQueryHandler
import io.vladar107.application.booking.GetEventTypeBySlugQuery
import io.vladar107.application.booking.GetEventTypeBySlugQueryHandler
import io.vladar107.application.booking.ListEventTypesQuery
import io.vladar107.application.booking.ListEventTypesQueryHandler
import io.vladar107.domain.booking.EventType
import io.vladar107.infrastructure.QueryHandler
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.provider

fun DI.MainBuilder.configureQueries() {
    bind<QueryHandler<AvailableSlots, FindAvailableSlotsQuery>>() with provider {
        FindAvailableSlotsQueryHandler(instance(), instance(), instance())
    }
    bind<QueryHandler<List<EventType>, ListEventTypesQuery>>() with provider {
        ListEventTypesQueryHandler(instance())
    }
    bind<QueryHandler<EventType?, GetEventTypeBySlugQuery>>() with provider {
        GetEventTypeBySlugQueryHandler(instance())
    }
    bind<QueryHandler<AvailableSlots?, FindEventTypeSlotsQuery>>() with provider {
        FindEventTypeSlotsQueryHandler(instance(), instance(), instance(), instance())
    }
}
