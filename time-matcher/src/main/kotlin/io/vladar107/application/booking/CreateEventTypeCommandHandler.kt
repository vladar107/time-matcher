package io.vladar107.application.booking

import io.vladar107.domain.booking.EventType
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler
import java.time.Duration
import java.util.UUID

data class CreateEventTypeCommand(
    val slug: String, val name: String, val duration: Duration, val bufferBefore: Duration, val bufferAfter: Duration,
) : Command<Unit>

class CreateEventTypeCommandHandler(private val repository: EventTypeRepository) : CommandHandler<Unit, CreateEventTypeCommand> {
    override suspend fun handle(command: CreateEventTypeCommand) = repository.create(
        EventType(UUID.randomUUID(), command.slug, command.name, command.duration, command.bufferBefore, command.bufferAfter, EventTypeStatus.ACTIVE))
}
