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
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.TimeInterval
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleCalendarApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun t(s: String) = Instant.parse(s)
    private val clock = Clock.fixed(t("2030-01-01T00:00:00Z"), ZoneId.of("UTC"))

    // A token client that always returns a valid access token (for GoogleTokenSource).
    private fun tokenSource() = GoogleTokenSource("cid", "sec", "rt",
        HttpClient(MockEngine { respond("""{"access_token":"AT","expires_in":3600}""", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) { json() } },
        clock)

    @Test fun freeBusyMapsBusyIntervals() = runBlocking {
        val body = """{"calendars":{"primary":{"busy":[{"start":"2030-01-07T10:00:00Z","end":"2030-01-07T11:00:00Z"}]}}}"""
        val api = GoogleCalendarApi(tokenSource(),
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) { json() } })
        val busy = api.freeBusy("primary", TimeInterval(t("2030-01-07T00:00:00Z"), t("2030-01-07T23:59:59Z")))
        assertEquals(listOf(TimeInterval(t("2030-01-07T10:00:00Z"), t("2030-01-07T11:00:00Z"))), busy)
    }

    @Test fun insertEventSendsAttendeeAndSucceeds() = runBlocking {
        var captured = ""
        val api = GoogleCalendarApi(tokenSource(),
            HttpClient(MockEngine { req -> captured = (req.body as io.ktor.http.content.TextContent).text; respond("""{"id":"evt1"}""", HttpStatusCode.OK, jsonHeaders) }) { install(ContentNegotiation) { json() } })
        api.insertEvent("primary", CalendarEvent(TimeInterval(t("2030-01-07T09:00:00Z"), t("2030-01-07T10:00:00Z")), "Intro with Sam", "Sam", "sam@example.com"))
        assertTrue(captured.contains("sam@example.com"))
        assertTrue(captured.contains("Intro with Sam"))
    }

    @Test fun nonSuccessThrowsCalendarException() = runBlocking<Unit> {
        val api = GoogleCalendarApi(tokenSource(),
            HttpClient(MockEngine { respond("""{"error":{"code":500}}""", HttpStatusCode.InternalServerError, jsonHeaders) }) { install(ContentNegotiation) { json() } })
        assertFailsWith<CalendarException> { api.freeBusy("primary", TimeInterval(t("2030-01-07T00:00:00Z"), t("2030-01-07T23:59:59Z"))) }
    }
}
