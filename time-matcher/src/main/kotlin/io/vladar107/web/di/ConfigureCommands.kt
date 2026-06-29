package io.vladar107.web.di

import io.vladar107.application.availability.AddBusyBlockCommand
import io.vladar107.application.availability.AddBusyBlockCommandHandler
import io.vladar107.application.booking.SetSettingsCommand
import io.vladar107.application.booking.SetSettingsCommandHandler
import io.vladar107.application.userCreation.CreatUserCommand
import io.vladar107.application.userCreation.CreateUserCommandHandler
import io.vladar107.infrastructure.CommandHandler
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.provider

fun DI.MainBuilder.configureCommands() {
    bind<CommandHandler<Unit, CreatUserCommand>>() with provider {
        CreateUserCommandHandler(instance())
    }
    bind<CommandHandler<Unit, AddBusyBlockCommand>>() with provider {
        AddBusyBlockCommandHandler(instance())
    }
    bind<CommandHandler<Unit, SetSettingsCommand>>() with provider {
        SetSettingsCommandHandler(instance())
    }
}
