package io.vladar107.application.booking

import io.vladar107.application.availability.CalendarProvider
import io.vladar107.application.availability.CalendarWriter
import io.vladar107.domain.availability.BusyInterval
import io.vladar107.domain.availability.CalendarEvent
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.TimeInterval
import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.ConnectedCalendar
import io.vladar107.domain.booking.EventType
import io.vladar107.domain.booking.EventTypeStatus
import io.vladar107.domain.booking.Settings
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookSlotCommandTest {
    private fun t(s: String) = Instant.parse(s)
    private val zone = ZoneId.of("UTC")
    private val et = EventType(UUID.randomUUID(), "intro", "Intro", Duration.ofMinutes(60), Duration.ZERO, Duration.ZERO, EventTypeStatus.ACTIVE)
    private val settings = Settings(zone,
        WeeklyAvailability(mapOf(DayOfWeek.MONDAY to listOf(LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(17, 0))))),
        emptyList(), Duration.ofMinutes(30), Duration.ZERO)

    private fun handler(written: CopyOnWriteArrayList<Pair<String, CalendarEvent>>): BookSlotCommandHandler {
        val store = CopyOnWriteArrayList<Pair<String, CalendarEvent>>()
        val provider = object : CalendarProvider {
            override suspend fun busyIntervals(window: TimeInterval) =
                store.filter { it.second.interval.overlaps(window) }.map { BusyInterval(it.second.interval, it.first) } }
        val writer = object : CalendarWriter {
            override suspend fun createEvent(calendarId: String, event: CalendarEvent) { store += calendarId to event; written += calendarId to event } }
        return BookSlotCommandHandler(
            object : EventTypeRepository {
                override suspend fun create(eventType: EventType) {}; override suspend fun list() = listOf(et)
                override suspend fun findBySlug(slug: String) = et.takeIf { it.slug == slug } },
            object : SettingsRepository { override suspend fun load() = settings; override suspend fun save(settings: Settings) {} },
            provider, writer,
            object : ConnectedCalendarRepository {
                override suspend fun list() = listOf(ConnectedCalendar(UUID.randomUUID(), "Default", "IN_MEMORY"))
                override suspend fun default() = ConnectedCalendar(UUID.randomUUID(), "Default", "IN_MEMORY")
                override suspend fun googleCalendars() = emptyList<ConnectedCalendar>()
                override suspend fun bookingTarget() = null
                override suspend fun add(calendar: ConnectedCalendar) {}
                override suspend fun remove(id: UUID) {}
                override suspend fun setBookingTarget(id: UUID) {} },
            Clock.fixed(t("2020-01-01T00:00:00Z"), zone))
    }

    @Test fun booksThenSecondBookingOfSameSlotIsTaken() = runBlocking {
        val written = CopyOnWriteArrayList<Pair<String, CalendarEvent>>()
        val h = handler(written)
        assertTrue(h.handle(BookSlotCommand("intro", "Sam", "sam@example.com", t("2030-01-07T09:00:00Z"))) is BookingResult.Booked)
        assertEquals(1, written.size)
        assertTrue(h.handle(BookSlotCommand("intro", "Pat", "pat@example.com", t("2030-01-07T09:00:00Z"))) is BookingResult.SlotTaken)
        assertEquals(1, written.size)
    }

    @Test fun unknownSlugIsNotFound() = runBlocking {
        assertTrue(handler(CopyOnWriteArrayList()).handle(BookSlotCommand("nope", "Sam", "s@e.com", t("2030-01-07T09:00:00Z"))) is BookingResult.EventTypeNotFound)
    }

    @Test fun asymmetricBufferBlocksAdjacentBusyEvent() = runBlocking {
        // bufferBefore=30min, bufferAfter=0: a busy event at [10:00,11:00] expands back to [09:30,11:00],
        // which overlaps the [09:00,10:00] slot — booking should be SlotTaken.
        val etAsym = EventType(UUID.randomUUID(), "intro", "Intro", Duration.ofMinutes(60),
            Duration.ofMinutes(30), Duration.ZERO, EventTypeStatus.ACTIVE)
        val store = CopyOnWriteArrayList<Pair<String, CalendarEvent>>()
        // Pre-seed a busy event at [10:00,11:00]
        store += "work" to CalendarEvent(
            TimeInterval(t("2030-01-07T10:00:00Z"), t("2030-01-07T11:00:00Z")), "(busy)")
        val provider = object : CalendarProvider {
            override suspend fun busyIntervals(window: TimeInterval) =
                store.filter { it.second.interval.overlaps(window) }.map { BusyInterval(it.second.interval, it.first) }
        }
        val writer = object : CalendarWriter {
            override suspend fun createEvent(calendarId: String, event: CalendarEvent) { store += calendarId to event }
        }
        val h = BookSlotCommandHandler(
            object : EventTypeRepository {
                override suspend fun create(eventType: EventType) {}
                override suspend fun list() = listOf(etAsym)
                override suspend fun findBySlug(slug: String) = etAsym.takeIf { it.slug == slug }
            },
            object : SettingsRepository { override suspend fun load() = settings; override suspend fun save(settings: Settings) {} },
            provider, writer,
            object : ConnectedCalendarRepository {
                override suspend fun list() = listOf(ConnectedCalendar(UUID.randomUUID(), "Default", "IN_MEMORY"))
                override suspend fun default() = ConnectedCalendar(UUID.randomUUID(), "Default", "IN_MEMORY")
                override suspend fun googleCalendars() = emptyList<ConnectedCalendar>()
                override suspend fun bookingTarget() = null
                override suspend fun add(calendar: ConnectedCalendar) {}
                override suspend fun remove(id: UUID) {}
                override suspend fun setBookingTarget(id: UUID) {}
            },
            Clock.fixed(t("2020-01-01T00:00:00Z"), zone))
        // Slot [09:00,10:00] should be blocked because the pre-buffer of the 10:00 busy event reaches 09:30
        assertEquals(BookingResult.SlotTaken,
            h.handle(BookSlotCommand("intro", "Sam", "sam@example.com", t("2030-01-07T09:00:00Z"))))
    }
}
