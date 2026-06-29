package io.vladar107.web.availability

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.vladar107.module
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPersistenceTest {
    @Test fun putConfigPersistsAndDrivesSlots() = testApplication {
        environment { config = MapApplicationConfig("db.url" to "jdbc:h2:mem:settings-test;DB_CLOSE_DELAY=-1") }
        application { module() }
        val put = client.put("/availability/config") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"zone":"UTC","granularityMinutes":60,"weekly":{"MONDAY":[{"start":"09:00","end":"17:00"}]},"overrides":[]}""")
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        val slots = client.get("/availability/slots") {
            parameter("from", "2030-01-07T00:00:00Z")
            parameter("to", "2030-01-07T23:59:59Z")
            parameter("duration", "PT1H")
        }
        assertEquals(HttpStatusCode.OK, slots.status)
        assert(slots.bodyAsText().contains("2030-01-07T09:00:00Z"))
    }
}
