package io.vladar107.domain.booking

import java.time.Duration
import java.util.UUID

enum class EventTypeStatus { ACTIVE, INACTIVE }

data class EventType(
    val id: UUID, val slug: String, val name: String,
    val duration: Duration, val bufferBefore: Duration, val bufferAfter: Duration, val status: EventTypeStatus,
)
