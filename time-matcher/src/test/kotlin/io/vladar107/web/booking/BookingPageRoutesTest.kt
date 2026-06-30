package io.vladar107.web.booking

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.vladar107.module
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookingPageRoutesTest {
    @Test fun servesBookingPageWiredToApi() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:page-${UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        application { module() }
        val r = client.get("/book/intro")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.contentType()?.match(ContentType.Text.Html) == true, "expected text/html")
        val body = r.bodyAsText()
        assertTrue(body.contains("/event-types/"), "page should fetch the event type")
        assertTrue(body.contains("/slots?from"), "page should fetch slots")
        assertTrue(body.contains("/book/"), "page should POST a booking")
    }
}
