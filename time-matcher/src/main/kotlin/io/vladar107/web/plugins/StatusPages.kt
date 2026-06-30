package io.vladar107.web.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.vladar107.application.availability.CalendarException
import io.vladar107.application.availability.NoBookingCalendarException

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<CalendarException> { call, _ ->
            call.respond(HttpStatusCode.BadGateway, "Calendar backend unavailable")
        }
        exception<NoBookingCalendarException> { call, _ ->
            call.respond(HttpStatusCode.ServiceUnavailable, "No booking calendar connected")
        }
    }
}
