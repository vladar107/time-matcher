package io.vladar107.web.di

import io.ktor.server.application.*
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import org.kodein.di.bind
import org.kodein.di.ktor.di
import org.kodein.di.provider

fun Application.configureDi() {
    di {
        bind<CommandProvider>() with provider { CommandProvider(this@configureDi) }
        bind<QueryProvider>() with provider { QueryProvider(this@configureDi) }
        configureCommands()
        configureQueries()
        configureRepositories()
        configureExternalServices()
    }
}
