package io.vladar107.application.booking

import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.domain.availability.AvailabilityEngine
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.SlotSearch
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.domain.booking.effectiveRules
import io.vladar107.infrastructure.Command
import io.vladar107.infrastructure.CommandHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

sealed interface BookingResult {
    data class Booked(val start: Instant, val end: Instant, val eventTypeName: String, val zone: ZoneId) : BookingResult
    data object SlotTaken : BookingResult
    data object EventTypeNotFound : BookingResult
}

data class BookSlotCommand(val slug: String, val attendeeName: String, val attendeeEmail: String, val start: Instant) : Command<BookingResult>

class BookSlotCommandHandler(
    private val eventTypeRepository: EventTypeRepository,
    private val settingsRepository: SettingsRepository,
    private val calendarProvider: CalendarProvider,
    private val calendarWriter: CalendarWriter,
    private val connectedCalendarRepository: ConnectedCalendarRepository,
    private val clock: Clock,
    private val engine: AvailabilityEngine = AvailabilityEngine(),
) : CommandHandler<BookingResult, BookSlotCommand> {
    private val mutex = Mutex()

    override suspend fun handle(command: BookSlotCommand): BookingResult {
        val et = eventTypeRepository.findBySlug(command.slug)?.takeIf { it.status == EventTypeStatus.ACTIVE }
            ?: return BookingResult.EventTypeNotFound
        val settings = settingsRepository.load()
        val rules = et.effectiveRules(settings)
        val end = command.start.plus(et.duration)
        return mutex.withLock {
            val pad = et.bufferBefore.plus(et.bufferAfter)
            val window = TimeInterval(command.start.minus(pad), end.plus(pad))
            val busy = calendarProvider.busyIntervals(window)
            val open = engine.findSlots(rules, busy, SlotSearch(command.start, end, et.duration), clock.instant())
            if (open.none { it.start == command.start }) BookingResult.SlotTaken
            else {
                val calendarId = connectedCalendarRepository.default().id.toString()
                calendarWriter.createEvent(calendarId,
                    CalendarEvent(TimeInterval(command.start, end), "${et.name} with ${command.attendeeName}", command.attendeeName, command.attendeeEmail))
                BookingResult.Booked(command.start, end, et.name, settings.zone)
            }
        }
    }
}
