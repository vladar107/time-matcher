package io.vladar107.web.booking

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.vladar107.application.booking.CreateEventTypeCommand
import io.vladar107.application.booking.GetEventTypeBySlugQuery
import io.vladar107.application.booking.ListEventTypesQuery
import io.vladar107.domain.booking.EventType
import io.vladar107.infrastructure.CommandProvider
import io.vladar107.infrastructure.QueryProvider
import io.vladar107.web.booking.dto.CreateEventTypeRequest
import io.vladar107.web.booking.dto.EventTypeDto
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.time.Duration

private fun EventType.toDto() = EventTypeDto(id.toString(), slug, name, duration.toMinutes(), bufferBefore.toMinutes(), bufferAfter.toMinutes(), status.name)

fun Application.configureEventTypes() {
    val commandProvider by closestDI { this@configureEventTypes }.instance<CommandProvider>()
    val queryProvider by closestDI { this@configureEventTypes }.instance<QueryProvider>()
    routing {
        route("/event-types") {
            post {
                val cmd = try {
                    val b = call.receive<CreateEventTypeRequest>()
                    require(b.slug.isNotBlank() && b.name.isNotBlank()) { "slug and name required" }
                    require(b.durationMinutes > 0) { "durationMinutes must be positive" }
                    require(b.bufferBeforeMinutes >= 0 && b.bufferAfterMinutes >= 0) { "buffers must be non-negative" }
                    CreateEventTypeCommand(b.slug, b.name, Duration.ofMinutes(b.durationMinutes), Duration.ofMinutes(b.bufferBeforeMinutes), Duration.ofMinutes(b.bufferAfterMinutes))
                } catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest, "Invalid event type: ${e.message}") }
                commandProvider.run(cmd); call.respond(HttpStatusCode.Created)
            }
            get { call.respond(queryProvider.query(ListEventTypesQuery()).map { it.toDto() }) }
            get("/{slug}") {
                val et: EventType? = queryProvider.query(GetEventTypeBySlugQuery(call.parameters["slug"]!!))
                if (et == null) call.respond(HttpStatusCode.NotFound, "Unknown event type") else call.respond(et.toDto())
            }
        }
    }
}
