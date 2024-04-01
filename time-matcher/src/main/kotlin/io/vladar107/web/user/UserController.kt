package io.vladar107.web.user

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.vladar107.application.userCreation.CreatUserCommand
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.web.user.dto.User
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI

fun Application.configureUser() {

    routing {
        route("/user") {
            val commandProvider by closestDI { this@configureUser }.instance<CommandProvider>()

            get<User> {
                call.respond("User")
            }
            post{
                val user = call.receive<User>()
                commandProvider.run(CreatUserCommand(user.name, user.email))

                call.respond(user)
            }
        }

    }
}
