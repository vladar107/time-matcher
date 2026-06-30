package io.vladar107.domain.booking

import java.util.UUID

data class ConnectedCalendar(
    val id: UUID,
    val name: String,
    val provider: String,
    val accountEmail: String? = null,
    val externalCalendarId: String? = null,
    val refreshToken: String? = null,
    val isBookingTarget: Boolean = false,
)
