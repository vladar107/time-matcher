package io.vladar107.domain.booking

import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class EffectiveRulesTest {
    @Test fun mergesSettingsWithEventTypeDurationAndBuffers() {
        val settings = Settings(
            ZoneId.of("Europe/Paris"),
            WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
            emptyList(), Duration.ofMinutes(30), Duration.ofHours(2),
        )
        val et = EventType(UUID.randomUUID(), "intro", "Intro", Duration.ofMinutes(45),
            Duration.ofMinutes(10), Duration.ofMinutes(5), EventTypeStatus.ACTIVE)
        val rules = et.effectiveRules(settings)
        assertEquals(settings.zone, rules.zone)
        assertEquals(settings.weekly, rules.weekly)
        assertEquals(settings.granularity, rules.granularity)
        assertEquals(settings.minimumNotice, rules.minimumNotice)
        assertEquals(Duration.ofMinutes(10), rules.bufferBefore)
        assertEquals(Duration.ofMinutes(5), rules.bufferAfter)
    }
}
