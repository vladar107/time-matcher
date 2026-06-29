package io.vladar107.data.repositories

import io.vladar107.application.booking.EventTypeRepository
import io.vladar107.data.persistence.EventTypeTable
import io.vladar107.domain.booking.EventType
import io.vladar107.domain.booking.EventTypeStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class ExposedEventTypeRepository : EventTypeRepository {
    private fun map(r: ResultRow) = EventType(
        r[EventTypeTable.id].toJavaUuid(),
        r[EventTypeTable.slug],
        r[EventTypeTable.name],
        Duration.ofMinutes(r[EventTypeTable.durationMinutes].toLong()),
        Duration.ofMinutes(r[EventTypeTable.bufferBeforeMinutes].toLong()),
        Duration.ofMinutes(r[EventTypeTable.bufferAfterMinutes].toLong()),
        EventTypeStatus.valueOf(r[EventTypeTable.status])
    )

    override suspend fun create(eventType: EventType): Unit = transaction {
        EventTypeTable.insert {
            it[id] = eventType.id.toKotlinUuid()
            it[slug] = eventType.slug
            it[name] = eventType.name
            it[durationMinutes] = eventType.duration.toMinutes().toInt()
            it[bufferBeforeMinutes] = eventType.bufferBefore.toMinutes().toInt()
            it[bufferAfterMinutes] = eventType.bufferAfter.toMinutes().toInt()
            it[status] = eventType.status.name
        }
    }

    override suspend fun list(): List<EventType> = transaction {
        EventTypeTable.selectAll().map(::map)
    }

    override suspend fun findBySlug(slug: String): EventType? = transaction {
        EventTypeTable.selectAll().where { EventTypeTable.slug eq slug }.singleOrNull()?.let(::map)
    }
}
