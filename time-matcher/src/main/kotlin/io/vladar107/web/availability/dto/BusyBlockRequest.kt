package io.vladar107.web.availability.dto

import kotlinx.serialization.Serializable

@Serializable
data class BusyBlockRequest(val start: String, val end: String)
