package io.vladar107.application.availability

import io.vladar107.domain.availability.TimeInterval
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler
import java.time.Instant

data class AddBusyBlockCommand(
    val calendarId: String,
    val start: Instant,
    val end: Instant,
) : Command<Unit>

class AddBusyBlockCommandHandler(
    private val writer: CalendarBusyWriter,
) : CommandHandler<Unit, AddBusyBlockCommand> {
    override suspend fun handle(command: AddBusyBlockCommand) {
        writer.addBusy(command.calendarId, TimeInterval(command.start, command.end))
    }
}
