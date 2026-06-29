package io.vladar107.web.availability

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.vladar107.application.availability.AddBusyBlockCommand
import io.vladar107.application.availability.FindAvailableSlotsQuery
import io.vladar107.application.availability.SetAvailabilityRulesCommand
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.web.availability.dto.AvailabilityConfigRequest
import io.vladar107.web.availability.dto.BusyBlockRequest
import io.vladar107.web.availability.dto.SlotDto
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val SLOT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")

fun Application.configureAvailability() {
    val commandProvider by closestDI { this@configureAvailability }.instance<CommandProvider>()
    val queryProvider by closestDI { this@configureAvailability }.instance<QueryProvider>()

    routing {
        get("/availability/slots") {
            val from = call.request.queryParameters["from"]
            val to = call.request.queryParameters["to"]
            val duration = call.request.queryParameters["duration"]
            val query = try {
                val fromInstant = Instant.parse(from)
                val toInstant = Instant.parse(to)
                val dur = Duration.parse(duration)
                require(fromInstant.isBefore(toInstant)) { "from must be before to" }
                require(!dur.isZero && !dur.isNegative) { "duration must be positive" }
                FindAvailableSlotsQuery(fromInstant, toInstant, dur)
            } catch (e: Exception) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid query: ${e.message}")
            }
            val result = queryProvider.query(query)
            call.respond(result.slots.map {
                SlotDto(
                    SLOT_FORMATTER.format(OffsetDateTime.ofInstant(it.start, result.zone)),
                    SLOT_FORMATTER.format(OffsetDateTime.ofInstant(it.end, result.zone)),
                )
            })
        }

        put("/availability/config") {
            val rules = try {
                call.receive<AvailabilityConfigRequest>().toRules()
            } catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, "Invalid config: ${e.message}")
            }
            commandProvider.run(SetAvailabilityRulesCommand(rules))
            call.respond(HttpStatusCode.NoContent)
        }

        post("/calendars/{calendarId}/busy") {
            val calendarId = call.parameters["calendarId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing calendarId")
            val command = try {
                val body = call.receive<BusyBlockRequest>()
                val start = Instant.parse(body.start)
                val end = Instant.parse(body.end)
                require(start.isBefore(end)) { "start must be before end" }
                AddBusyBlockCommand(calendarId, start, end)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid busy block: ${e.message}")
            }
            commandProvider.run(command)
            call.respond(HttpStatusCode.Created)
        }
    }
}
