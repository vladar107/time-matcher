package io.vladar107.data.google

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.vladar107.application.availability.CalendarException
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.TimeInterval
import kotlinx.serialization.Serializable
import java.time.Instant

// ---- DTOs ----
@Serializable private data class FbItem(val id: String)
@Serializable private data class FreeBusyRequest(val timeMin: String, val timeMax: String, val items: List<FbItem>)
@Serializable private data class FbSlot(val start: String, val end: String)
@Serializable private data class FbError(val domain: String? = null, val reason: String? = null)
@Serializable private data class FbCalendar(val busy: List<FbSlot> = emptyList(), val errors: List<FbError> = emptyList())
@Serializable private data class FreeBusyResponse(val calendars: Map<String, FbCalendar> = emptyMap())
@Serializable private data class EventTime(val dateTime: String)
@Serializable private data class EventAttendee(val email: String, val displayName: String? = null)
@Serializable private data class EventInsertRequest(val summary: String, val start: EventTime, val end: EventTime, val attendees: List<EventAttendee> = emptyList())

class GoogleCalendarApi(
    private val tokenSource: GoogleTokenSource,
    private val httpClient: HttpClient,
) {
    suspend fun freeBusy(calendarId: String, window: TimeInterval): List<TimeInterval> {
        val resp = call {
            httpClient.post("https://www.googleapis.com/calendar/v3/freeBusy") {
                header(HttpHeaders.Authorization, "Bearer ${tokenSource.accessToken()}")
                contentType(ContentType.Application.Json)
                setBody(FreeBusyRequest(window.start.toString(), window.end.toString(), listOf(FbItem(calendarId))))
            }
        }
        val parsed = resp.body<FreeBusyResponse>()
        val cal = parsed.calendars[calendarId]
            ?: throw CalendarException("freeBusy error for calendar $calendarId: missing")
        if (cal.errors.isNotEmpty()) {
            val reasons = cal.errors.joinToString { it.reason ?: it.domain ?: "unknown" }
            throw CalendarException("freeBusy error for calendar $calendarId: $reasons")
        }
        return cal.busy.map { TimeInterval(Instant.parse(it.start), Instant.parse(it.end)) }
    }

    suspend fun insertEvent(calendarId: String, event: CalendarEvent) {
        val attendees = if (event.attendeeEmail != null) listOf(EventAttendee(event.attendeeEmail, event.attendeeName)) else emptyList()
        call {
            httpClient.post("https://www.googleapis.com/calendar/v3/calendars/$calendarId/events?sendUpdates=all") {
                header(HttpHeaders.Authorization, "Bearer ${tokenSource.accessToken()}")
                contentType(ContentType.Application.Json)
                setBody(EventInsertRequest(event.title, EventTime(event.interval.start.toString()), EventTime(event.interval.end.toString()), attendees))
            }
        }
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse {
        val resp = try { block() } catch (e: CalendarException) { throw e } catch (e: Exception) { throw CalendarException("Google Calendar request failed", e) }
        if (!resp.status.isSuccess()) throw CalendarException("Google Calendar error: ${resp.status}")
        return resp
    }
}
