package io.vladar107.domain.availability

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

object DayHoursFixtures {
    fun mondayNineToFive(zone: ZoneId): AvailabilityRules = AvailabilityRules(
        zone = zone,
        weekly = WeeklyAvailability(
            mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0)))),
        ),
        granularity = Duration.ofMinutes(30),
    )
}
