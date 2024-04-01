package io.vladar107.data.repositories

import io.vladar107.application.userCreation.UserCreationRepository
import io.vladar107.domain.User

class UserRepository: UserCreationRepository {
    override suspend fun createUser(user: User): User {
        throw NotImplementedError("Not implemented")
    }
}
