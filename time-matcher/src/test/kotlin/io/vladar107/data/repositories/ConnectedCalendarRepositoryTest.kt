package io.vladar107.data.repositories

import io.vladar107.data.persistence.Db
import io.vladar107.domain.booking.ConnectedCalendar
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectedCalendarRepositoryTest {
    private lateinit var repo: ExposedConnectedCalendarRepository
    @BeforeTest fun setup() { Db.init("jdbc:h2:mem:cc-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"); repo = ExposedConnectedCalendarRepository() }

    private fun google(email: String) = ConnectedCalendar(UUID.randomUUID(), email, "GOOGLE", email, "primary", "rt-$email")

    @Test fun firstGoogleIsNotAutoTargetAtRepoLevel() = runBlocking {
        val c = google("a@x.com"); repo.add(c)
        assertEquals(listOf("a@x.com"), repo.googleCalendars().map { it.accountEmail })
        assertNull(repo.bookingTarget()) // repo.add stores isBookingTarget as given (false here)
    }

    @Test fun setBookingTargetIsExclusiveAmongGoogleRows() = runBlocking {
        val a = google("a@x.com"); val b = google("b@x.com"); repo.add(a); repo.add(b)
        repo.setBookingTarget(a.id)
        assertEquals("a@x.com", repo.bookingTarget()?.accountEmail)
        repo.setBookingTarget(b.id)
        assertEquals("b@x.com", repo.bookingTarget()?.accountEmail)
        assertEquals(1, repo.googleCalendars().count { it.isBookingTarget })
    }

    @Test fun removeTargetAutoPromotesAnotherGoogleRow() = runBlocking {
        val a = google("a@x.com"); val b = google("b@x.com"); repo.add(a); repo.add(b)
        repo.setBookingTarget(a.id); repo.remove(a.id)
        assertEquals("b@x.com", repo.bookingTarget()?.accountEmail) // promoted
        repo.remove(b.id)
        assertNull(repo.bookingTarget())
        assertTrue(repo.googleCalendars().isEmpty())
    }

    @Test fun setBookingTargetWithUnknownIdLeavesCurrentTargetIntact() = runBlocking {
        val a = google("a@x.com"); val b = google("b@x.com"); repo.add(a); repo.add(b)
        repo.setBookingTarget(a.id)
        assertEquals("a@x.com", repo.bookingTarget()?.accountEmail)
        repo.setBookingTarget(java.util.UUID.randomUUID()) // unknown id — must be a no-op
        assertEquals("a@x.com", repo.bookingTarget()?.accountEmail)
    }

    @Test fun googleCalendarsExcludesSeededInMemoryRow() = runBlocking {
        assertEquals("IN_MEMORY", repo.default().provider) // seed still present
        assertTrue(repo.googleCalendars().isEmpty())
    }
}
