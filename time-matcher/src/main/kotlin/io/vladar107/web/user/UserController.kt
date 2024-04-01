package io.vladar107.web.user

import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.vladar107.web.user.dto.User

fun Application.configureUser() {
    routing {
        get<User> {
            call.respond("User")
        }
        post ( "/user" ) {
            call.respondText("User Created")
        }
    }
}
