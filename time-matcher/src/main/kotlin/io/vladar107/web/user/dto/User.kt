package io.vladar107.web.user.dto

import io.ktor.resources.*
import kotlinx.serialization.Serializable

@Serializable
@Resource("/user")
data class User (
    val name: String,
    val email: String? = null
) {
    fun io.vladar107.domain.User.toDTO(domainUser: io.vladar107.domain.User): User {
        return User(domainUser.name, domainUser.email)
    }
}
