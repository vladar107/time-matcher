package io.vladar107.web.availability.dto

import kotlinx.serialization.Serializable

@Serializable
data class SlotDto(val start: String, val end: String)
