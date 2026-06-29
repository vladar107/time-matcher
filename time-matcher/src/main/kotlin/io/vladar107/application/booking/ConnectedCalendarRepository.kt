package io.vladar107.application.booking

import io.vladar107.domain.booking.ConnectedCalendar

interface ConnectedCalendarRepository { suspend fun list(): List<ConnectedCalendar>; suspend fun default(): ConnectedCalendar }
