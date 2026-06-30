package io.vladar107.web.booking

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureBookingPage() {
    val html = (object {}.javaClass.getResource("/web/booking.html")
        ?: error("web/booking.html not found on classpath")).readText()
    routing {
        get("/book/{slug}") {
            call.respondText(html, ContentType.Text.Html)
        }
    }
}
