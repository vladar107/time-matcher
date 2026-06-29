package io.vladar107.application.availability

import io.vladar107.application.booking.SettingsRepository
import io.vladar107.domain.availability.AvailabilityEngine
import io.vladar107.domain.availability.AvailabilityRules
import io.vladar107.domain.availability.SlotSearch
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class AvailableSlots(val zone: ZoneId, val slots: List<TimeInterval>)

data class FindAvailableSlotsQuery(
    val from: Instant,
    val to: Instant,
    val duration: Duration,
) : Query<AvailableSlots>

class FindAvailableSlotsQueryHandler(
    private val calendarProvider: CalendarProvider,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : QueryHandler<AvailableSlots, FindAvailableSlotsQuery> {

    override suspend fun handle(query: FindAvailableSlotsQuery): AvailableSlots {
        val s = settingsRepository.load()
        val rules = AvailabilityRules(s.zone, s.weekly, s.overrides, s.granularity, Duration.ZERO, Duration.ZERO, s.minimumNotice)
        val busy = calendarProvider.busyIntervals(TimeInterval(query.from, query.to))
        val slots = engine.findSlots(rules, busy, SlotSearch(query.from, query.to, query.duration), clock.instant())
        return AvailableSlots(s.zone, slots)
    }
}
