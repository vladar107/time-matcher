package io.vladar107.application.booking

import io.vladar107.domain.booking.Settings
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler

data class SetSettingsCommand(val settings: Settings) : Command<Unit>

class SetSettingsCommandHandler(private val settingsRepository: SettingsRepository) : CommandHandler<Unit, SetSettingsCommand> {
    override suspend fun handle(command: SetSettingsCommand) = settingsRepository.save(command.settings)
}
