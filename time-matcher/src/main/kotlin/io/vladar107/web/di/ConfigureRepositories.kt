package io.vladar107.web.di

import io.vladar107.application.availability.AvailabilityConfigRepository
import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.userCreation.UserCreationRepository
import io.vladar107.data.repositories.InMemoryAvailabilityConfigRepository
import io.vladar107.data.repositories.InMemoryCalendarProvider
import io.vladar107.data.repositories.UserRepository
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.provider
import org.kodein.di.singleton

fun DI.MainBuilder.configureRepositories() {
    bind<UserCreationRepository>() with provider { UserRepository() }

    // Stateful in-memory store shared across read (CalendarProvider) and write (CalendarBusyWriter).
    bind<InMemoryCalendarProvider>() with singleton { InMemoryCalendarProvider() }
    bind<CalendarProvider>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<CalendarBusyWriter>() with singleton { instance<InMemoryCalendarProvider>() }

    bind<AvailabilityConfigRepository>() with singleton { InMemoryAvailabilityConfigRepository() }
}
