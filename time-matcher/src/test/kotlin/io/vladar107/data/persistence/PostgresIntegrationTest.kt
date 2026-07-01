package io.vladar107.data.persistence

import io.vladar107.data.repositories.ExposedConnectedCalendarRepository
import io.vladar107.data.repositories.ExposedEventTypeRepository
import io.vladar107.data.repositories.ExposedSettingsRepository
import io.vladar107.domain.booking.ConnectedCalendar
import io.vladar107.domain.booking.EventType
import io.vladar107.domain.booking.EventTypeStatus
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresIntegrationTest {
    private lateinit var pg: PostgreSQLContainer<*>
    @BeforeTest fun start() {
        pg = PostgreSQLContainer("postgres:17").withDatabaseName("tm").withUsername("tm").withPassword("tm")
        pg.start()
        Db.init(pg.jdbcUrl, pg.username, pg.password)
    }
    @AfterTest fun stop() = pg.stop()

    @Test fun migrationsApplyAndSettingsSeedOnRealPostgres() = runBlocking {
        // V1 seeds Europe/Paris settings + one IN_MEMORY calendar; V2 adds OAuth columns.
        assertEquals("Europe/Paris", ExposedSettingsRepository().load().zone.id)
        assertEquals("IN_MEMORY", ExposedConnectedCalendarRepository().default().provider)
    }

    @Test fun connectedCalendarUuidAndBooleanRoundTripOnPostgres() = runBlocking {
        val repo = ExposedConnectedCalendarRepository()
        val a = ConnectedCalendar(UUID.randomUUID(), "a@x.com", "GOOGLE", "a@x.com", "primary", "rt", isBookingTarget = true)
        repo.add(a)
        val loaded = repo.googleCalendars().single()
        assertEquals("a@x.com", loaded.accountEmail)
        assertEquals(true, loaded.isBookingTarget)  // BOOLEAN column round-trips
    }

    @Test fun eventTypeRoundTripsOnPostgres() = runBlocking {
        val repo = ExposedEventTypeRepository()
        repo.create(EventType(UUID.randomUUID(), "intro", "Intro", Duration.ofMinutes(30), Duration.ZERO, Duration.ZERO, EventTypeStatus.ACTIVE))
        assertEquals("Intro", repo.findBySlug("intro")?.name)
    }
}
