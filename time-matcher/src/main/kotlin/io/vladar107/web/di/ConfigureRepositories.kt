package io.vladar107.web.di

import io.vladar107.application.booking.ConnectedCalendarRepository
import io.vladar107.application.booking.EventTypeRepository
import io.vladar107.application.booking.SettingsRepository
import io.vladar107.application.userCreation.UserCreationRepository
import io.vladar107.data.repositories.ExposedConnectedCalendarRepository
import io.vladar107.data.repositories.ExposedEventTypeRepository
import io.vladar107.data.repositories.ExposedSettingsRepository
import io.vladar107.data.repositories.UserRepository
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton

fun DI.MainBuilder.configureRepositories() {
    bind<UserCreationRepository>() with singleton { UserRepository() }

    bind<SettingsRepository>() with singleton { ExposedSettingsRepository() }
    bind<EventTypeRepository>() with singleton { ExposedEventTypeRepository() }
    bind<ConnectedCalendarRepository>() with singleton { ExposedConnectedCalendarRepository() }
}
