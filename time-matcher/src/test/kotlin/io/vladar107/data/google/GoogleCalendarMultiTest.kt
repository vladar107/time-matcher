package io.vladar107.data.google

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.vladar107.application.availability.NoBookingCalendarException
import io.vladar107.application.booking.ConnectedCalendarRepository
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.domain.booking.ConnectedCalendar
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleCalendarMultiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun t(s: String) = Instant.parse(s)
    private val clock = Clock.fixed(t("2030-01-01T00:00:00Z"), ZoneId.of("UTC"))
    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } }
    private fun tokens() = GoogleTokenManager("cid", "sec",
        client { respond("""{"access_token":"AT","expires_in":3600}""", HttpStatusCode.OK, jsonHeaders) }, clock)

    private class FakeRepo(private val rows: List<ConnectedCalendar>) : ConnectedCalendarRepository {
        override suspend fun list() = rows
        override suspend fun default() = rows.first()
        override suspend fun googleCalendars() = rows.filter { it.provider == "GOOGLE" }
        override suspend fun bookingTarget() = rows.firstOrNull { it.provider == "GOOGLE" && it.isBookingTarget }
        override suspend fun add(calendar: ConnectedCalendar) {}
        override suspend fun remove(id: UUID) {}
        override suspend fun setBookingTarget(id: UUID) {}
    }
    private fun g(email: String, target: Boolean = false) =
        ConnectedCalendar(UUID.randomUUID(), email, "GOOGLE", email, "primary", "rt-$email", target)

    @Test fun busyIsUnionedAcrossAllConnectedCalendars() = runBlocking {
        val repo = FakeRepo(listOf(g("a@x.com"), g("b@x.com")))
        // freeBusy always reports the queried calendar (id "primary") busy 10-11 for each account.
        val api = GoogleCalendarApi(client { respond(
            """{"calendars":{"primary":{"busy":[{"start":"2030-01-07T10:00:00Z","end":"2030-01-07T11:00:00Z"}]}}}""",
            HttpStatusCode.OK, jsonHeaders) })
        val provider = GoogleCalendarProvider(repo, tokens(), api)
        val busy = provider.busyIntervals(TimeInterval(t("2030-01-07T00:00:00Z"), t("2030-01-07T23:59:59Z")))
        assertEquals(2, busy.size) // one per connected calendar
        assertTrue(busy.all { it.interval == TimeInterval(t("2030-01-07T10:00:00Z"), t("2030-01-07T11:00:00Z")) })
    }

    @Test fun bookingWritesToTheTargetCalendar() = runBlocking {
        val repo = FakeRepo(listOf(g("a@x.com"), g("b@x.com", target = true)))
        var captured = ""
        val api = GoogleCalendarApi(client { req -> captured = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"id":"evt1"}""", HttpStatusCode.OK, jsonHeaders) })
        GoogleCalendarWriter(repo, tokens(), api).createEvent("ignored",
            CalendarEvent(TimeInterval(t("2030-01-07T09:00:00Z"), t("2030-01-07T10:00:00Z")), "Intro with Sam", "Sam", "sam@example.com"))
        assertTrue(captured.contains("Intro with Sam"))
    }

    @Test fun bookingWithNoTargetThrows() = runBlocking<Unit> {
        val repo = FakeRepo(listOf(g("a@x.com"))) // present but not a target
        val api = GoogleCalendarApi(client { respond("{}", HttpStatusCode.OK, jsonHeaders) })
        assertFailsWith<NoBookingCalendarException> {
            GoogleCalendarWriter(repo, tokens(), api).createEvent("ignored",
                CalendarEvent(TimeInterval(t("2030-01-07T09:00:00Z"), t("2030-01-07T10:00:00Z")), "x", "n", "e@e.com"))
        }
    }
}
