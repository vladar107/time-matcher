package io.vladar107.domain.availability

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityEngineTest {
    private val engine = AvailabilityEngine()
    private fun t(s: String): Instant = Instant.parse(s)
    private fun range(a: String, b: String) = LocalTimeRange(LocalTime.parse(a), LocalTime.parse(b))

    private fun rules(
        zone: String = "UTC",
        granularityMinutes: Long = 30,
        monday: List<LocalTimeRange> = listOf(range("09:00", "17:00")),
        overrides: List<DateOverride> = emptyList(),
    ) = AvailabilityRules(
        zone = ZoneId.of(zone),
        weekly = WeeklyAvailability(mapOf(DayOfWeek.MONDAY to monday)),
        overrides = overrides,
        granularity = Duration.ofMinutes(granularityMinutes),
    )

    // Monday 2030-01-07, whole-day window
    private fun mondaySearch(durationMinutes: Long) = SlotSearch(
        from = t("2030-01-07T00:00:00Z"),
        to = t("2030-01-07T23:59:59Z"),
        duration = Duration.ofMinutes(durationMinutes),
    )

    private val longAgo = t("2020-01-01T00:00:00Z")

    @Test
    fun emptyDayYieldsNoSlots() {
        // Tuesday window with only Monday configured
        val search = SlotSearch(t("2030-01-08T00:00:00Z"), t("2030-01-08T23:59:59Z"), Duration.ofMinutes(60))
        assertEquals(emptyList(), engine.findSlots(rules(), emptyList(), search, longAgo))
    }

    @Test
    fun fullWorkingDaySlicedIntoSlots() {
        // 09:00-17:00, 60-min meetings, 30-min grid, no busy -> 09:00,09:30,...,16:00
        val slots = engine.findSlots(rules(), emptyList(), mondaySearch(60), longAgo)
        assertEquals(t("2030-01-07T09:00:00Z"), slots.first().start)
        assertEquals(t("2030-01-07T16:00:00Z"), slots.last().start)
        assertEquals(15, slots.size)
    }

    @Test
    fun slotsAlignToCleanGridAfterABusyBlock() {
        // busy 10:00-10:20; with 30-min grid the next slot must be 10:30, not 10:20
        val busy = listOf(BusyInterval(TimeInterval(t("2030-01-07T10:00:00Z"), t("2030-01-07T10:20:00Z")), "work"))
        val slots = engine.findSlots(rules(), busy, mondaySearch(60), longAgo)
        // 09:00 fits (09:00-10:00). No 09:30 (would hit busy). Next is 10:30.
        assertEquals(t("2030-01-07T09:00:00Z"), slots[0].start)
        assertEquals(t("2030-01-07T10:30:00Z"), slots[1].start)
        assertTrue(slots.none { it.start == t("2030-01-07T10:20:00Z") })
    }

    @Test
    fun slotThatDoesNotFullyFitIsExcluded() {
        // Single 09:00-09:59 window via override; a 60-min meeting cannot fit.
        val tightDay = rules(overrides = listOf(DateOverride(java.time.LocalDate.parse("2030-01-07"), listOf(range("09:00", "09:59")))))
        assertEquals(emptyList(), engine.findSlots(tightDay, emptyList(), mondaySearch(60), longAgo))
    }

    @Test
    fun projectsWorkingHoursIntoConfiguredZone() {
        // 09:00 in Europe/Paris (summer, UTC+2) == 07:00Z. Use a July Monday: 2030-07-01.
        val search = SlotSearch(t("2030-07-01T00:00:00Z"), t("2030-07-01T23:59:59Z"), Duration.ofMinutes(60))
        val parisRules = AvailabilityRules(
            zone = ZoneId.of("Europe/Paris"),
            weekly = WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(range("09:00", "17:00")))),
            granularity = Duration.ofMinutes(30),
        )
        val slots = engine.findSlots(parisRules, emptyList(), search, longAgo)
        assertEquals(t("2030-07-01T07:00:00Z"), slots.first().start) // 09:00 Paris == 07:00Z
    }

    @Test
    fun handlesSpringForwardDstDay() {
        // Europe/Paris spring-forward is 2030-03-31 (02:00->03:00). A Sunday.
        // Configure SUNDAY 00:00-06:00; the missing hour means 5 real hours, so 5 one-hour slots.
        val date = java.time.LocalDate.parse("2030-03-31")
        val dstRules = AvailabilityRules(
            zone = ZoneId.of("Europe/Paris"),
            weekly = WeeklyAvailability(mapOf(DayOfWeek.SUNDAY to listOf(range("00:00", "06:00")))),
            granularity = Duration.ofMinutes(60),
        )
        val search = SlotSearch(
            from = date.atStartOfDay(ZoneId.of("Europe/Paris")).toInstant(),
            to = date.atTime(LocalTime.parse("06:00")).atZone(ZoneId.of("Europe/Paris")).toInstant(),
            duration = Duration.ofMinutes(60),
        )
        val slots = engine.findSlots(dstRules, emptyList(), search, longAgo)
        assertEquals(5, slots.size)
    }
}
