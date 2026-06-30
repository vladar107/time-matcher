package io.vladar107.application.booking

import io.vladar107.domain.booking.ConnectedCalendar
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectedCalendarCommandsTest {
    // Minimal in-memory fake of the port.
    private class FakeRepo : ConnectedCalendarRepository {
        val rows = mutableListOf<ConnectedCalendar>()
        override suspend fun list() = rows.toList()
        override suspend fun default() = rows.first()
        override suspend fun googleCalendars() = rows.filter { it.provider == "GOOGLE" }
        override suspend fun bookingTarget() = rows.firstOrNull { it.provider == "GOOGLE" && it.isBookingTarget }
        override suspend fun add(calendar: ConnectedCalendar) { rows += calendar }
        override suspend fun remove(id: UUID) { rows.removeIf { it.id == id } }
        override suspend fun setBookingTarget(id: UUID) { rows.replaceAll { it.copy(isBookingTarget = it.id == id) } }
    }

    @Test fun firstConnectedGoogleCalendarBecomesBookingTarget() = runBlocking {
        val repo = FakeRepo(); val h = ConnectGoogleCalendarCommandHandler(repo)
        h.handle(ConnectGoogleCalendarCommand("a@x.com", "primary", "rt1"))
        assertEquals("a@x.com", repo.bookingTarget()?.accountEmail)
        h.handle(ConnectGoogleCalendarCommand("b@x.com", "primary", "rt2"))
        assertEquals("a@x.com", repo.bookingTarget()?.accountEmail) // second does NOT steal the target
        assertEquals(2, repo.googleCalendars().size)
    }

    @Test fun setTargetAndRemoveDelegateToRepo() = runBlocking {
        val repo = FakeRepo()
        ConnectGoogleCalendarCommandHandler(repo).handle(ConnectGoogleCalendarCommand("a@x.com", "primary", "rt1"))
        ConnectGoogleCalendarCommandHandler(repo).handle(ConnectGoogleCalendarCommand("b@x.com", "primary", "rt2"))
        val b = repo.googleCalendars().first { it.accountEmail == "b@x.com" }
        SetBookingTargetCommandHandler(repo).handle(SetBookingTargetCommand(b.id))
        assertEquals("b@x.com", repo.bookingTarget()?.accountEmail)
        RemoveConnectedCalendarCommandHandler(repo).handle(RemoveConnectedCalendarCommand(b.id))
        assertTrue(repo.googleCalendars().none { it.accountEmail == "b@x.com" })
        assertEquals(listOf("a@x.com"), ListConnectedCalendarsQueryHandler(repo).handle(ListConnectedCalendarsQuery()).map { it.accountEmail })
    }
}
