package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityEngine
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
    private val configRepository: AvailabilityConfigRepository,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : QueryHandler<AvailableSlots, FindAvailableSlotsQuery> {

    override suspend fun handle(query: FindAvailableSlotsQuery): AvailableSlots {
        val rules = configRepository.load()
        val window = TimeInterval(query.from, query.to)
        val busy = calendarProvider.busyIntervals(window)
        val slots = engine.findSlots(rules, busy, SlotSearch(query.from, query.to, query.duration), clock.instant())
        return AvailableSlots(rules.zone, slots)
    }
}
