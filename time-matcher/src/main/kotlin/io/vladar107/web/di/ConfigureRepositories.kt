package io.vladar107.web.di

import io.vladar107.application.userCreation.UserCreationRepository
import io.vladar107.data.repositories.UserRepository
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.provider

fun DI.MainBuilder.configureRepositories() {
    bind<UserCreationRepository>() with provider { UserRepository() }
}
