package io.vladar107.web.booking

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.vladar107.module
import io.vladar107.web.availability.dto.SlotDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookingRoutesTest {
    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test fun bookingRemovesTheSlotAndSecondBookingConflicts() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:book-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        application { module() }
        val client = jsonClient()
        client.put("/availability/config") { contentType(ContentType.Application.Json)
            setBody("""{"zone":"UTC","granularityMinutes":60,"weekly":{"MONDAY":[{"start":"09:00","end":"17:00"}]},"overrides":[]}""") }
        client.post("/event-types") { contentType(ContentType.Application.Json)
            setBody("""{"slug":"intro","name":"Intro","durationMinutes":60}""") }
        val before = Json.decodeFromString<List<SlotDto>>(
            client.get("/book/intro/slots") { parameter("from", "2030-01-07T00:00:00Z"); parameter("to", "2030-01-07T23:59:59Z") }.bodyAsText())
        assertTrue(before.any { it.start == "2030-01-07T09:00:00Z" })
        assertEquals(HttpStatusCode.Created, client.post("/book/intro") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"Sam","attendeeEmail":"sam@example.com","start":"2030-01-07T09:00:00Z"}""") }.status)
        val after = Json.decodeFromString<List<SlotDto>>(
            client.get("/book/intro/slots") { parameter("from", "2030-01-07T00:00:00Z"); parameter("to", "2030-01-07T23:59:59Z") }.bodyAsText())
        assertTrue(after.none { it.start == "2030-01-07T09:00:00Z" })
        assertEquals(HttpStatusCode.Conflict, client.post("/book/intro") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"Pat","attendeeEmail":"pat@example.com","start":"2030-01-07T09:00:00Z"}""") }.status)
    }

    @Test fun bookingUnknownEventTypeIs404() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:book404-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        application { module() }
        assertEquals(HttpStatusCode.NotFound, jsonClient().post("/book/nope") { contentType(ContentType.Application.Json)
            setBody("""{"attendeeName":"X","attendeeEmail":"x@e.com","start":"2030-01-07T09:00:00Z"}""") }.status)
    }
}
