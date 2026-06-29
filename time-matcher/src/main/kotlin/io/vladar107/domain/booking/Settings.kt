package io.vladar107.domain.booking

import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.WeeklyAvailability
import java.time.Duration
import java.time.ZoneId

data class Settings(
    val zone: ZoneId, val weekly: WeeklyAvailability, val overrides: List<DateOverride>,
    val granularity: Duration, val minimumNotice: Duration,
)
