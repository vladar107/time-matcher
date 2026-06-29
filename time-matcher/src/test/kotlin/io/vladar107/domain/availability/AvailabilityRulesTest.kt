package io.vladar107.domain.availability

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class AvailabilityRulesTest {
    private fun range(a: String, b: String) = LocalTimeRange(LocalTime.parse(a), LocalTime.parse(b))

    private fun rules(overrides: List<DateOverride> = emptyList()) = AvailabilityRules(
        zone = ZoneId.of("UTC"),
        weekly = WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(range("09:00", "17:00")))),
        overrides = overrides,
        granularity = Duration.ofMinutes(30),
    )

    @Test
    fun usesWeekdayHoursWhenNoOverride() {
        // 2030-01-07 is a Monday
        assertEquals(listOf(range("09:00", "17:00")), rules().rangesFor(LocalDate.parse("2030-01-07")))
    }

    @Test
    fun emptyForDayWithNoWeekdayHours() {
        // 2030-01-12 is a Saturday (not configured)
        assertEquals(emptyList(), rules().rangesFor(LocalDate.parse("2030-01-12")))
    }

    @Test
    fun overrideReplacesWeekdayHours() {
        val date = LocalDate.parse("2030-01-07")
        val withOverride = rules(listOf(DateOverride(date, listOf(range("10:00", "12:00")))))
        assertEquals(listOf(range("10:00", "12:00")), withOverride.rangesFor(date))
    }

    @Test
    fun overrideWithEmptyRangesMarksDayUnavailable() {
        val date = LocalDate.parse("2030-01-07")
        val dayOff = rules(listOf(DateOverride(date, emptyList())))
        assertEquals(emptyList(), dayOff.rangesFor(date))
    }
}
