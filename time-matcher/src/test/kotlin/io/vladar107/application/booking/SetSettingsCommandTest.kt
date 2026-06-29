package io.vladar107.application.booking

import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.Settings
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class SetSettingsCommandTest {
    @Test fun savesSettings() = runBlocking {
        var saved: Settings? = null
        val repo = object : SettingsRepository {
            override suspend fun load() = error("unused")
            override suspend fun save(settings: Settings) { saved = settings }
        }
        val s = Settings(ZoneId.of("UTC"), WeeklyAvailability(emptyMap()), emptyList(), Duration.ofMinutes(30), Duration.ZERO)
        SetSettingsCommandHandler(repo).handle(SetSettingsCommand(s))
        assertEquals(s, saved)
    }
}
