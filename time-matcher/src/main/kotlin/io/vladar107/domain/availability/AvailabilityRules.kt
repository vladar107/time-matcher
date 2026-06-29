package io.vladar107.domain.availability

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Half-open local-time range [start, end) within a single day. */
data class LocalTimeRange(val start: LocalTime, val end: LocalTime) {
    init { require(start.isBefore(end)) { "start must be before end: $start..$end" } }
}

/** Recurring working hours keyed by day of week. Multiple ranges per day model breaks (e.g. lunch). */
data class WeeklyAvailability(val byDay: Map<DayOfWeek, List<LocalTimeRange>>) {
    fun rangesFor(day: DayOfWeek): List<LocalTimeRange> = byDay[day] ?: emptyList()
}

/** Exception for a specific date. Empty [ranges] means the day is unavailable. */
data class DateOverride(val date: LocalDate, val ranges: List<LocalTimeRange>)

data class AvailabilityRules(
    val zone: ZoneId,
    val weekly: WeeklyAvailability,
    val overrides: List<DateOverride> = emptyList(),
    val granularity: Duration,
    val bufferBefore: Duration = Duration.ZERO,
    val bufferAfter: Duration = Duration.ZERO,
    val minimumNotice: Duration = Duration.ZERO,
) {
    /** Override for the date if present, otherwise the weekday hours. */
    fun rangesFor(date: LocalDate): List<LocalTimeRange> =
        overrides.firstOrNull { it.date == date }?.ranges ?: weekly.rangesFor(date.dayOfWeek)
}
