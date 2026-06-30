package io.vladar107.data.persistence

import org.jetbrains.exposed.v1.core.Table

object SettingsTable : Table("settings") {
    val id = integer("id"); val timezone = varchar("timezone", 64)
    val granularityMinutes = integer("granularity_minutes"); val minimumNoticeMinutes = integer("minimum_notice_minutes")
    override val primaryKey = PrimaryKey(id)
}

object WorkingHoursTable : Table("working_hours") {
    val id = uuid("id"); val dayOfWeek = varchar("day_of_week", 9)
    val startTime = varchar("start_time", 8); val endTime = varchar("end_time", 8)
    override val primaryKey = PrimaryKey(id)
}

object DateOverrideTable : Table("date_override") {
    val id = uuid("id"); val date = varchar("override_date", 10)
    val startTime = varchar("start_time", 8).nullable(); val endTime = varchar("end_time", 8).nullable()
    override val primaryKey = PrimaryKey(id)
}

object EventTypeTable : Table("event_type") {
    val id = uuid("id"); val slug = varchar("slug", 128).uniqueIndex(); val name = varchar("name", 256)
    val durationMinutes = integer("duration_minutes"); val bufferBeforeMinutes = integer("buffer_before_minutes")
    val bufferAfterMinutes = integer("buffer_after_minutes"); val status = varchar("status", 16)
    override val primaryKey = PrimaryKey(id)
}

object ConnectedCalendarTable : Table("connected_calendar") {
    val id = uuid("id"); val name = varchar("name", 256); val provider = varchar("provider", 32); val createdAt = varchar("created_at", 64)
    val accountEmail = varchar("account_email", 256).nullable()
    val externalCalendarId = varchar("external_calendar_id", 256).nullable()
    val refreshToken = varchar("refresh_token", 512).nullable()
    val isBookingTarget = bool("is_booking_target")
    override val primaryKey = PrimaryKey(id)
}
