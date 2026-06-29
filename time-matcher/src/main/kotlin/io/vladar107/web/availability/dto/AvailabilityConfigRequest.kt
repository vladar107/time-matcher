package io.vladar107.web.availability.dto

import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class LocalTimeRangeDto(val start: String, val end: String)

@Serializable
data class DateOverrideDto(val date: String, val ranges: List<LocalTimeRangeDto>)

@Serializable
data class AvailabilityConfigRequest(
    val zone: String,
    val granularityMinutes: Long,
    val bufferBeforeMinutes: Long = 0,
    val bufferAfterMinutes: Long = 0,
    val minimumNoticeMinutes: Long = 0,
    val weekly: Map<String, List<LocalTimeRangeDto>> = emptyMap(),
    val overrides: List<DateOverrideDto> = emptyList(),
) {
    /** Parse into domain rules. Throws IllegalArgumentException / DateTimeException on bad input. */
    fun toRules(): AvailabilityRules {
        require(granularityMinutes > 0) { "granularityMinutes must be positive" }
        require(bufferBeforeMinutes >= 0) { "bufferBeforeMinutes must be non-negative" }
        require(bufferAfterMinutes >= 0) { "bufferAfterMinutes must be non-negative" }
        require(minimumNoticeMinutes >= 0) { "minimumNoticeMinutes must be non-negative" }
        fun range(dto: LocalTimeRangeDto) = LocalTimeRange(LocalTime.parse(dto.start), LocalTime.parse(dto.end))
        return AvailabilityRules(
            zone = ZoneId.of(zone),
            weekly = WeeklyAvailability(
                weekly.entries.associate { (day, ranges) ->
                    DayOfWeek.valueOf(day.uppercase()) to ranges.map(::range)
                },
            ),
            overrides = overrides.map { DateOverride(LocalDate.parse(it.date), it.ranges.map(::range)) },
            granularity = Duration.ofMinutes(granularityMinutes),
            bufferBefore = Duration.ofMinutes(bufferBeforeMinutes),
            bufferAfter = Duration.ofMinutes(bufferAfterMinutes),
            minimumNotice = Duration.ofMinutes(minimumNoticeMinutes),
        )
    }
}
