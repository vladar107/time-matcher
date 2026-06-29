package io.vladar107.data.repositories

import io.vladar107.application.availability.AvailabilityConfigRepository
import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/** In-memory availability config seeded with a default. Bind as a Kodein singleton. */
class InMemoryAvailabilityConfigRepository(
    initial: AvailabilityRules = default,
) : AvailabilityConfigRepository {

    @Volatile
    private var rules: AvailabilityRules = initial

    override suspend fun load(): AvailabilityRules = rules

    override suspend fun save(rules: AvailabilityRules) {
        this.rules = rules
    }

    companion object {
        val default: AvailabilityRules = AvailabilityRules(
            zone = ZoneId.of("Europe/Paris"),
            weekly = WeeklyAvailability(
                DayOfWeek.entries
                    .filter { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }
                    .associateWith { listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))) },
            ),
            granularity = Duration.ofMinutes(30),
        )
    }
}
