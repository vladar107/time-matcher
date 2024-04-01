package io.vladar107.application.userCreation

import io.vladar107.domain.User
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler

data class CreatUserCommand<Unit>(val name: String, val email: String? = null) :
    Command<Unit>

class UserCreationHandler(private val userCreationRepository: UserCreationRepository) :
    CommandHandler<Unit, CreatUserCommand<Unit>> {
    override suspend fun handle(command: CreatUserCommand<Unit>) {
        userCreationRepository.createUser(User(command.name, command.email))
    }
}
