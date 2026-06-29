package io.vladar107.web.booking

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.vladar107.application.availability.AvailableSlots
import io.vladar107.application.booking.BookSlotCommand
import io.vladar107.application.booking.BookingResult
import io.vladar107.application.booking.FindEventTypeSlotsQuery
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.web.availability.dto.SlotDto
import io.vladar107.web.booking.dto.BookRequest
import io.vladar107.web.booking.dto.BookingConfirmation
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")
private fun render(instant: Instant, zone: ZoneId) = OffsetDateTime.ofInstant(instant, zone).format(ISO)

fun Application.configureBooking() {
    val commandProvider by closestDI { this@configureBooking }.instance<CommandProvider>()
    val queryProvider by closestDI { this@configureBooking }.instance<QueryProvider>()
    routing {
        route("/book/{slug}") {
            get("/slots") {
                val slug = call.parameters["slug"]!!
                val query = try {
                    val f = Instant.parse(call.request.queryParameters["from"]); val t = Instant.parse(call.request.queryParameters["to"])
                    require(f.isBefore(t)) { "from must be before to" }; FindEventTypeSlotsQuery(slug, f, t)
                } catch (e: Exception) { return@get call.respond(HttpStatusCode.BadRequest, "Invalid query: ${e.message}") }
                val result: AvailableSlots? = queryProvider.query(query)
                if (result == null) return@get call.respond(HttpStatusCode.NotFound, "Unknown event type")
                call.respond(result.slots.map { SlotDto(render(it.start, result.zone), render(it.end, result.zone)) })
            }
            post {
                val slug = call.parameters["slug"]!!
                val cmd = try {
                    val b = call.receive<BookRequest>()
                    require(b.attendeeName.isNotBlank() && b.attendeeEmail.isNotBlank()) { "attendee required" }
                    BookSlotCommand(slug, b.attendeeName, b.attendeeEmail, Instant.parse(b.start))
                } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid booking: ${e.message}") }
                when (val r: BookingResult = commandProvider.run(cmd)) {
                    is BookingResult.Booked -> call.respond(HttpStatusCode.Created, BookingConfirmation(render(r.start, r.zone), render(r.end, r.zone), r.eventTypeName))
                    BookingResult.SlotTaken -> call.respond(HttpStatusCode.Conflict, "Slot no longer available")
                    BookingResult.EventTypeNotFound -> call.respond(HttpStatusCode.NotFound, "Unknown event type")
                }
            }
        }
    }
}
