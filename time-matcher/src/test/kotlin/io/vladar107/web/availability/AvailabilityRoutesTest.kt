package io.vladar107.web.availability

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.vladar107.module
import io.vladar107.web.availability.dto.SlotDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityRoutesTest {

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun findsSlotsAroundBusyBlocksAcrossCalendars() = testApplication {
        application { module() }
        val client = jsonClient()

        // Configure UTC, Monday 09:00-17:00, 30-min grid.
        val config = client.put("/availability/config") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "zone": "UTC",
                  "granularityMinutes": 30,
                  "weekly": { "MONDAY": [ { "start": "09:00", "end": "17:00" } ] },
                  "overrides": []
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.NoContent, config.status)

        // Busy 10:00-11:00 on the work calendar.
        val busy = client.post("/calendars/work/busy") {
            contentType(ContentType.Application.Json)
            setBody("""{ "start": "2030-01-07T10:00:00Z", "end": "2030-01-07T11:00:00Z" }""")
        }
        assertEquals(HttpStatusCode.Created, busy.status)

        // Find 1-hour slots on Monday 2030-01-07.
        val response = client.get("/availability/slots") {
            parameter("from", "2030-01-07T00:00:00Z")
            parameter("to", "2030-01-07T23:59:59Z")
            parameter("duration", "PT1H")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val slots = Json.decodeFromString<List<SlotDto>>(response.bodyAsText())

        assertEquals("2030-01-07T09:00:00Z", slots.first().start)
        assertTrue(slots.none { it.start == "2030-01-07T10:00:00Z" }) // inside busy
        assertEquals(12, slots.size) // 09:00 + 11:00,11:30,...,16:00
    }

    @Test
    fun rejectsInvalidWindow() = testApplication {
        application { module() }
        val response = jsonClient().get("/availability/slots") {
            parameter("from", "2030-01-07T12:00:00Z")
            parameter("to", "2030-01-07T09:00:00Z") // to < from
            parameter("duration", "PT1H")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
