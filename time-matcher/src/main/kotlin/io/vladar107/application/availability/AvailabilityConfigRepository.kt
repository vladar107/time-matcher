package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityRules

interface AvailabilityConfigRepository {
    suspend fun load(): AvailabilityRules
    suspend fun save(rules: AvailabilityRules)
}
