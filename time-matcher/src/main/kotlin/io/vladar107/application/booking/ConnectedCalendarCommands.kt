package io.vladar107.application.booking

import io.vladar107.domain.booking.ConnectedCalendar
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler
import java.util.UUID

data class ConnectGoogleCalendarCommand(val accountEmail: String, val externalCalendarId: String, val refreshToken: String) : Command<Unit>
data class RemoveConnectedCalendarCommand(val id: UUID) : Command<Unit>
data class SetBookingTargetCommand(val id: UUID) : Command<Unit>

class ConnectGoogleCalendarCommandHandler(private val repo: ConnectedCalendarRepository) : CommandHandler<Unit, ConnectGoogleCalendarCommand> {
    override suspend fun handle(command: ConnectGoogleCalendarCommand) {
        val firstGoogle = repo.googleCalendars().isEmpty()
        repo.add(ConnectedCalendar(UUID.randomUUID(), command.accountEmail, "GOOGLE",
            command.accountEmail, command.externalCalendarId, command.refreshToken, isBookingTarget = firstGoogle))
    }
}

class RemoveConnectedCalendarCommandHandler(private val repo: ConnectedCalendarRepository) : CommandHandler<Unit, RemoveConnectedCalendarCommand> {
    override suspend fun handle(command: RemoveConnectedCalendarCommand) = repo.remove(command.id)
}

class SetBookingTargetCommandHandler(private val repo: ConnectedCalendarRepository) : CommandHandler<Unit, SetBookingTargetCommand> {
    override suspend fun handle(command: SetBookingTargetCommand) = repo.setBookingTarget(command.id)
}
