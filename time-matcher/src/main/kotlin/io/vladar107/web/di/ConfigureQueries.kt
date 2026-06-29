package io.vladar107.web.di

import io.vladar107.application.availability.AvailableSlots
import io.vladar107.application.availability.FindAvailableSlotsQuery
import io.vladar107.application.availability.FindAvailableSlotsQueryHandler
import io.vladar107.infrastructure.QueryHandler
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.provider

fun DI.MainBuilder.configureQueries() {
    bind<QueryHandler<AvailableSlots, FindAvailableSlotsQuery>>() with provider {
        FindAvailableSlotsQueryHandler(instance(), instance(), instance())
    }
}
