package io.vladar107.application.userCreation

import io.vladar107.domain.User
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler

data class CreatUserCommand(val name: String, val email: String? = null) : Command<Unit>

class CreateUserCommandHandler(private val userCreationRepository: UserCreationRepository) :
    CommandHandler<Unit, CreatUserCommand> {
    override suspend fun handle(command: CreatUserCommand) {
        userCreationRepository.createUser(User(command.name, command.email))
    }
}
