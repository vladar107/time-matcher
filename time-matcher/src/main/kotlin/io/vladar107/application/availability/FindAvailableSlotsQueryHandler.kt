package io.vladar107.application.availability

import io.vladar107.domain.availability.AvailabilityEngine
import io.vladar107.domain.availability.SlotSearch
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class FindAvailableSlotsQuery(
    val from: Instant,
    val to: Instant,
    val duration: Duration,
) : Query<List<TimeInterval>>

class FindAvailableSlotsQueryHandler(
    private val calendarProvider: CalendarProvider,
    private val configRepository: AvailabilityConfigRepository,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : QueryHandler<List<TimeInterval>, FindAvailableSlotsQuery> {

    override suspend fun handle(query: FindAvailableSlotsQuery): List<TimeInterval> {
        val rules = configRepository.load()
        val window = TimeInterval(query.from, query.to)
        val busy = calendarProvider.busyIntervals(window)
        return engine.findSlots(rules, busy, SlotSearch(query.from, query.to, query.duration), clock.instant())
    }
}
