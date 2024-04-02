package io.vladar107.web.plugins

import io.ktor.server.resources.Resources
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*

fun Application.configureSerialization() {
    install(Resources)
    install(ContentNegotiation) {
        json()
    }
}
