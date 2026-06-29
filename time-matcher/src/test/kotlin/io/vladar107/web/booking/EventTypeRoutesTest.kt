package io.vladar107.web.booking

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.vladar107.module
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventTypeRoutesTest {
    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test fun createsAndListsEventTypes() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:et-${java.util.UUID.randomUUID()};DB_CLOSE_DELAY=-1") }
        application { module() }
        val client = jsonClient()
        val create = client.post("/event-types") {
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"intro","name":"Intro call","durationMinutes":30,"bufferBeforeMinutes":0,"bufferAfterMinutes":0}""")
        }
        assertEquals(HttpStatusCode.Created, create.status)
        assertTrue(client.get("/event-types").bodyAsText().contains("intro"))
        assertEquals(HttpStatusCode.OK, client.get("/event-types/intro").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/event-types/missing").status)
    }
}
