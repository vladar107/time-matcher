package io.vladar107.data.google

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.vladar107.application.availability.CalendarException
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleTokenSourceTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(calls: AtomicInteger, status: HttpStatusCode = HttpStatusCode.OK, body: String = """{"access_token":"AT-1","expires_in":3600}""") =
        HttpClient(MockEngine { _ -> calls.incrementAndGet(); respond(body, status, jsonHeaders) }) {
            install(ContentNegotiation) { json() }
        }

    @Test fun refreshesOnceThenCaches() = runBlocking {
        val calls = AtomicInteger()
        val clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneId.of("UTC"))
        val src = GoogleTokenSource("cid", "secret", "rt", client(calls), clock)
        assertEquals("AT-1", src.accessToken())
        assertEquals("AT-1", src.accessToken()) // cached, no second HTTP call
        assertEquals(1, calls.get())
    }

    @Test fun throwsCalendarExceptionOnTokenError() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val src = GoogleTokenSource("cid", "secret", "rt",
            client(calls, HttpStatusCode.Unauthorized, """{"error":"invalid_grant"}"""),
            Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneId.of("UTC")))
        assertFailsWith<CalendarException> { src.accessToken() }
        Unit
    }
}
