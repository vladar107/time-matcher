package io.vladar107.data.repositories

import io.vladar107.application.booking.ConnectedCalendarRepository
import io.vladar107.data.persistence.ConnectedCalendarTable
import io.vladar107.domain.booking.ConnectedCalendar
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.toJavaUuid

class ExposedConnectedCalendarRepository : ConnectedCalendarRepository {
    private fun map(r: ResultRow) = ConnectedCalendar(
        r[ConnectedCalendarTable.id].toJavaUuid(),
        r[ConnectedCalendarTable.name],
        r[ConnectedCalendarTable.provider]
    )

    override suspend fun list(): List<ConnectedCalendar> = transaction {
        ConnectedCalendarTable.selectAll().map(::map)
    }

    override suspend fun default(): ConnectedCalendar = transaction {
        ConnectedCalendarTable.selectAll().first().let(::map)
    }
}
