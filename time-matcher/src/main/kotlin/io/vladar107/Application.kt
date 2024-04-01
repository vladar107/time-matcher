package io.vladar107

import io.ktor.server.application.*
import io.vladar107.application.userCreation.CreatUserCommand
import io.vladar107.application.userCreation.CreateUserCommandHandler
import io.vladar107.application.userCreation.UserCreationRepository
import io.vladar107.data.repositories.UserRepository
import io.vladar107.infrastructure.CommandHandler
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.web.documentation.configureOpenAPI
import io.vladar107.web.monitoring.configureMonitoring
import io.vladar107.web.plugins.configureRouting
import io.vladar107.web.plugins.configureSerialization
import io.vladar107.web.user.configureUser
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.ktor.di
import org.kodein.di.provider

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    di {
        bind<CommandProvider>() with provider { CommandProvider(this@module) }
        bind<QueryProvider>() with provider { QueryProvider(this@module) }
        bind<CommandHandler<Unit, CreatUserCommand>>() with provider {
            CreateUserCommandHandler(
                instance()
            )
        }
        bind<UserCreationRepository>() with provider { UserRepository() }
    }
    configureOpenAPI()
    configureMonitoring()
    configureSerialization()
    configureRouting()
    configureUser()
}
