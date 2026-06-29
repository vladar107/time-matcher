package io.vladar107

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun userRouteResponds() = testApplication {
        application { module() }
        val response = client.get("/user")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
