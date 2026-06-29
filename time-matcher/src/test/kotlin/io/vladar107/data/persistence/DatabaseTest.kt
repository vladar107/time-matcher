package io.vladar107.data.persistence

import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseTest {
    @Test fun migratesAndSeedsConfig() {
        Db.init("jdbc:h2:mem:smoke;DB_CLOSE_DELAY=-1")
        transaction {
            assertEquals(1, SettingsTable.selectAll().count().toInt())
            assertEquals("Europe/Paris", SettingsTable.selectAll().single()[SettingsTable.timezone])
            assertEquals(1, ConnectedCalendarTable.selectAll().count().toInt())
        }
    }
}
