package io.vladar107.application.booking

import io.vladar107.domain.booking.ConnectedCalendar

interface ConnectedCalendarRepository {
    suspend fun list(): List<ConnectedCalendar>
    suspend fun default(): ConnectedCalendar                // unchanged: first row (seeded IN_MEMORY)
    suspend fun googleCalendars(): List<ConnectedCalendar>  // provider == "GOOGLE"
    suspend fun bookingTarget(): ConnectedCalendar?         // the GOOGLE row with isBookingTarget == true
    suspend fun add(calendar: ConnectedCalendar)
    suspend fun remove(id: java.util.UUID)
    suspend fun setBookingTarget(id: java.util.UUID)        // sets this GOOGLE row true, all other GOOGLE rows false
}
