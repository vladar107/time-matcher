package io.vladar107

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun applicationModuleBootsAndServesMetrics() = testApplication {
        application { module() }
        // Smoke test: the module wires up (DI, monitoring, routing) and serves a stable route.
        val response = client.get("/metrics-micrometer")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
