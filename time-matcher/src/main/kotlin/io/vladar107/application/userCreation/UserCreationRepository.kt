package io.vladar107.application.userCreation

import io.vladar107.domain.User

interface UserCreationRepository {
    suspend fun createUser(user: User): User
}
