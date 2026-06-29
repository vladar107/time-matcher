package io.vladar107.data.repositories

import io.vladar107.application.booking.SettingsRepository
import io.vladar107.data.persistence.DateOverrideTable
import io.vladar107.data.persistence.SettingsTable
import io.vladar107.data.persistence.WorkingHoursTable
import io.vladar107.domain.availability.DateOverride
import io.vladar107.domain.availability.LocalTimeRange
import io.vladar107.domain.availability.WeeklyAvailability
import io.vladar107.domain.booking.Settings
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.uuid.toKotlinUuid

class ExposedSettingsRepository : SettingsRepository {
    override suspend fun load(): Settings = transaction {
        val row = SettingsTable.selectAll().single()
        val weekly = WorkingHoursTable.selectAll()
            .groupBy { DayOfWeek.valueOf(it[WorkingHoursTable.dayOfWeek]) }
            .mapValues { (_, rows) ->
                rows.map {
                    LocalTimeRange(
                        LocalTime.parse(it[WorkingHoursTable.startTime]),
                        LocalTime.parse(it[WorkingHoursTable.endTime])
                    )
                }
            }
        val overrides = DateOverrideTable.selectAll().map {
            val s = it[DateOverrideTable.startTime]
            val e = it[DateOverrideTable.endTime]
            DateOverride(
                LocalDate.parse(it[DateOverrideTable.date]),
                if (s != null && e != null) listOf(LocalTimeRange(LocalTime.parse(s), LocalTime.parse(e))) else emptyList()
            )
        }
        Settings(
            ZoneId.of(row[SettingsTable.timezone]),
            WeeklyAvailability(weekly),
            overrides,
            Duration.ofMinutes(row[SettingsTable.granularityMinutes].toLong()),
            Duration.ofMinutes(row[SettingsTable.minimumNoticeMinutes].toLong())
        )
    }

    override suspend fun save(settings: Settings): Unit = transaction {
        SettingsTable.update({ SettingsTable.id eq 1 }) {
            it[timezone] = settings.zone.id
            it[granularityMinutes] = settings.granularity.toMinutes().toInt()
            it[minimumNoticeMinutes] = settings.minimumNotice.toMinutes().toInt()
        }
        WorkingHoursTable.deleteAll()
        settings.weekly.byDay.forEach { (day, ranges) ->
            ranges.forEach { r ->
                WorkingHoursTable.insert {
                    it[id] = UUID.randomUUID().toKotlinUuid()
                    it[dayOfWeek] = day.name
                    it[startTime] = r.start.toString()
                    it[endTime] = r.end.toString()
                }
            }
        }
        DateOverrideTable.deleteAll()
        settings.overrides.forEach { o ->
            DateOverrideTable.insert {
                it[id] = UUID.randomUUID().toKotlinUuid()
                it[date] = o.date.toString()
                it[startTime] = o.ranges.firstOrNull()?.start?.toString()
                it[endTime] = o.ranges.firstOrNull()?.end?.toString()
            }
        }
    }
}
