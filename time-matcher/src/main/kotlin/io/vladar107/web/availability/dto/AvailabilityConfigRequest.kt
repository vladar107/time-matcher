package io.vladar107.web.availability.dto

import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.Settings
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable data class LocalTimeRangeDto(val start: String, val end: String)
@Serializable data class DateOverrideDto(val date: String, val ranges: List<LocalTimeRangeDto>)

@Serializable
data class AvailabilityConfigRequest(
    val zone: String,
    val granularityMinutes: Long,
    val minimumNoticeMinutes: Long = 0,
    val weekly: Map<String, List<LocalTimeRangeDto>> = emptyMap(),
    val overrides: List<DateOverrideDto> = emptyList(),
) {
    fun toSettings(): Settings {
        require(granularityMinutes > 0) { "granularityMinutes must be positive" }
        require(minimumNoticeMinutes >= 0) { "minimumNoticeMinutes must be non-negative" }
        fun range(d: LocalTimeRangeDto) = LocalTimeRange(LocalTime.parse(d.start), LocalTime.parse(d.end))
        return Settings(
            zone = ZoneId.of(zone),
            weekly = WeeklyAvailability(weekly.entries.associate { (day, r) -> DayOfWeek.valueOf(day.uppercase()) to r.map(::range) }),
            overrides = overrides.map { DateOverride(LocalDate.parse(it.date), it.ranges.map(::range)) },
            granularity = Duration.ofMinutes(granularityMinutes),
            minimumNotice = Duration.ofMinutes(minimumNoticeMinutes),
        )
    }
}
