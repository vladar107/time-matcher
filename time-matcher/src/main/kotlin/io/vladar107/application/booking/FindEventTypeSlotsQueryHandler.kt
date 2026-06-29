package io.vladar107.application.booking

import io.vladar107.application.availability.AvailableSlots
import io.vladar107.application.availability.CalendarProvider
import io.vladar107.domain.availability.AvailabilityEngine
import io.vladar107.domain.availability.SlotSearch
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.domain.booking.effectiveRules
import io.vladar107.infrastructure.Query
import io.vladar107.infrastructure.QueryHandler
import java.time.Clock
import java.time.Instant

data class FindEventTypeSlotsQuery(val slug: String, val from: Instant, val to: Instant) : Query<AvailableSlots?>

class FindEventTypeSlotsQueryHandler(
    private val eventTypeRepository: EventTypeRepository,
    private val settingsRepository: SettingsRepository,
    private val calendarProvider: CalendarProvider,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : QueryHandler<AvailableSlots?, FindEventTypeSlotsQuery> {
    override suspend fun handle(query: FindEventTypeSlotsQuery): AvailableSlots? {
        val et = eventTypeRepository.findBySlug(query.slug)?.takeIf { it.status == EventTypeStatus.ACTIVE } ?: return null
        val settings = settingsRepository.load()
        val busy = calendarProvider.busyIntervals(TimeInterval(query.from, query.to))
        val slots = engine.findSlots(et.effectiveRules(settings), busy, SlotSearch(query.from, query.to, et.duration), clock.instant())
        return AvailableSlots(settings.zone, slots)
    }
}
