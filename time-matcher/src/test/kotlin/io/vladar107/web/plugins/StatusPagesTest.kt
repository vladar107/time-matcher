package io.vladar107.web.plugins

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.vladar107.application.availability.CalendarException
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusPagesTest {
    @Test fun calendarExceptionMapsTo502() = testApplication {
        application {
            configureStatusPages()
            routing { get("/boom") { throw CalendarException("boom") } }
        }
        assertEquals(HttpStatusCode.BadGateway, client.get("/boom").status)
    }
}
