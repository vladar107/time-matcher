package io.vladar107.web.di

import io.vladar107.application.availability.CalendarBusyWriter
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.application.booking.ConnectedCalendarRepository
import io.vladar107.application.booking.EventTypeRepository
import io.vladar107.application.booking.SettingsRepository
import io.vladar107.application.userCreation.UserCreationRepository
import io.vladar107.data.repositories.ExposedConnectedCalendarRepository
import io.vladar107.data.repositories.ExposedEventTypeRepository
import io.vladar107.data.repositories.ExposedSettingsRepository
import io.vladar107.data.repositories.InMemoryCalendarProvider
import io.vladar107.data.repositories.UserRepository
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.singleton

fun DI.MainBuilder.configureRepositories() {
    bind<UserCreationRepository>() with singleton { UserRepository() }

    // Stateful in-memory store shared across read (CalendarProvider), write (CalendarBusyWriter), and event creation (CalendarWriter).
    bind<InMemoryCalendarProvider>() with singleton { InMemoryCalendarProvider() }
    bind<CalendarProvider>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<CalendarBusyWriter>() with singleton { instance<InMemoryCalendarProvider>() }
    bind<CalendarWriter>() with singleton { instance<InMemoryCalendarProvider>() }

    bind<SettingsRepository>() with singleton { ExposedSettingsRepository() }
    bind<EventTypeRepository>() with singleton { ExposedEventTypeRepository() }
    bind<ConnectedCalendarRepository>() with singleton { ExposedConnectedCalendarRepository() }
}
