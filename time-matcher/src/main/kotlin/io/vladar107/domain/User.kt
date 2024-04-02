package io.vladar107.domain

import java.util.UUID

class User(val name: String, val email: String? = null) {
    val id: UUID = UUID.randomUUID()

}
