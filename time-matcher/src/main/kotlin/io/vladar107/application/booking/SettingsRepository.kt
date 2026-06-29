package io.vladar107.application.booking

import io.vladar107.domain.booking.Settings

interface SettingsRepository { suspend fun load(): Settings; suspend fun save(settings: Settings) }
