package io.vladar107.domain.booking

import io.vladar107.domain.availability.AvailabilityRules

/** Engine input for an event type: host-global Settings + this type's buffers. */
fun EventType.effectiveRules(settings: Settings): AvailabilityRules = AvailabilityRules(
    zone = settings.zone, weekly = settings.weekly, overrides = settings.overrides,
    granularity = settings.granularity, bufferBefore = bufferBefore, bufferAfter = bufferAfter,
    minimumNotice = settings.minimumNotice,
)
