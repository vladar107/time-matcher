package io.vladar107.data.repositories

import io.vladar107.application.booking.ConnectedCalendarRepository
import io.vladar107.data.persistence.ConnectedCalendarTable
import io.vladar107.domain.booking.ConnectedCalendar
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class ExposedConnectedCalendarRepository : ConnectedCalendarRepository {
    private fun map(r: ResultRow) = ConnectedCalendar(
        r[ConnectedCalendarTable.id].toJavaUuid(),
        r[ConnectedCalendarTable.name],
        r[ConnectedCalendarTable.provider],
        r[ConnectedCalendarTable.accountEmail],
        r[ConnectedCalendarTable.externalCalendarId],
        r[ConnectedCalendarTable.refreshToken],
        r[ConnectedCalendarTable.isBookingTarget],
    )

    override suspend fun list(): List<ConnectedCalendar> = transaction {
        ConnectedCalendarTable.selectAll().map(::map)
    }

    override suspend fun default(): ConnectedCalendar = transaction {
        ConnectedCalendarTable.selectAll().orderBy(ConnectedCalendarTable.createdAt, SortOrder.ASC).firstOrNull()?.let(::map)
            ?: error("No connected calendar configured")
    }

    override suspend fun googleCalendars(): List<ConnectedCalendar> = transaction {
        ConnectedCalendarTable.selectAll().where { ConnectedCalendarTable.provider eq "GOOGLE" }.map(::map)
    }

    override suspend fun bookingTarget(): ConnectedCalendar? = transaction {
        ConnectedCalendarTable.selectAll()
            .where { (ConnectedCalendarTable.provider eq "GOOGLE") and (ConnectedCalendarTable.isBookingTarget eq true) }
            .firstOrNull()?.let(::map)
    }

    override suspend fun add(calendar: ConnectedCalendar) { transaction {
        ConnectedCalendarTable.insert {
            it[id] = calendar.id.toKotlinUuid()
            it[name] = calendar.name
            it[provider] = calendar.provider
            it[createdAt] = java.time.Instant.now().toString()
            it[accountEmail] = calendar.accountEmail
            it[externalCalendarId] = calendar.externalCalendarId
            it[refreshToken] = calendar.refreshToken
            it[isBookingTarget] = calendar.isBookingTarget
        }
    } }

    override suspend fun setBookingTarget(id: java.util.UUID) { transaction {
        val isGoogleRow = ConnectedCalendarTable.selectAll()
            .where { (ConnectedCalendarTable.id eq id.toKotlinUuid()) and (ConnectedCalendarTable.provider eq "GOOGLE") }
            .count() > 0
        if (!isGoogleRow) return@transaction
        ConnectedCalendarTable.update({ ConnectedCalendarTable.provider eq "GOOGLE" }) { it[isBookingTarget] = false }
        ConnectedCalendarTable.update({ ConnectedCalendarTable.id eq id.toKotlinUuid() }) { it[isBookingTarget] = true }
    } }

    override suspend fun remove(id: java.util.UUID) { transaction {
        val wasTarget = ConnectedCalendarTable.selectAll()
            .where { ConnectedCalendarTable.id eq id.toKotlinUuid() }
            .firstOrNull()?.get(ConnectedCalendarTable.isBookingTarget) ?: false
        ConnectedCalendarTable.deleteWhere { ConnectedCalendarTable.id eq id.toKotlinUuid() }
        if (wasTarget) {
            val next = ConnectedCalendarTable.selectAll()
                .where { ConnectedCalendarTable.provider eq "GOOGLE" }
                .orderBy(ConnectedCalendarTable.createdAt, SortOrder.ASC)
                .firstOrNull()
            if (next != null) {
                ConnectedCalendarTable.update({ ConnectedCalendarTable.id eq next[ConnectedCalendarTable.id] }) { it[isBookingTarget] = true }
            }
        }
    } }
}
