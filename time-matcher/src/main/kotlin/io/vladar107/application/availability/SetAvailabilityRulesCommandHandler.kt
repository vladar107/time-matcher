package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler

data class SetAvailabilityRulesCommand(val rules: AvailabilityRules) : Command<Unit>

class SetAvailabilityRulesCommandHandler(
    private val repository: AvailabilityConfigRepository,
) : CommandHandler<Unit, SetAvailabilityRulesCommand> {
    override suspend fun handle(command: SetAvailabilityRulesCommand) {
        repository.save(command.rules)
    }
}
