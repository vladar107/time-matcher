package io.vladar107.domain.availability

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

data class SlotSearch(val from: Instant, val to: Instant, val duration: Duration)

class AvailabilityEngine {

    fun findSlots(
        rules: AvailabilityRules,
        busy: List<BusyInterval>,
        search: SlotSearch,
        now: Instant,
    ): List<TimeInterval> {
        val effectiveStart = maxOf(search.from, now.plus(rules.minimumNotice))
        if (!effectiveStart.isBefore(search.to)) return emptyList()

        // Expand busy by buffers, then merge into a minimal blocked set.
        val blocked = busy
            .map {
                TimeInterval(
                    it.interval.start.minus(rules.bufferBefore),
                    it.interval.end.plus(rules.bufferAfter),
                )
            }
            .merged()

        val startDate = effectiveStart.atZone(rules.zone).toLocalDate()
        val endDate = search.to.atZone(rules.zone).toLocalDate()

        val slots = mutableListOf<TimeInterval>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            val ranges = rules.rangesFor(date)
            if (ranges.isNotEmpty()) {
                // Grid anchor: the day's earliest working-range start, as an Instant.
                val anchorInstant = ZonedDateTime.of(date, ranges.minOf { it.start }, rules.zone).toInstant()

                val working = ranges.mapNotNull { r ->
                    val s = maxOf(ZonedDateTime.of(date, r.start, rules.zone).toInstant(), effectiveStart)
                    val e = minOf(ZonedDateTime.of(date, r.end, rules.zone).toInstant(), search.to)
                    if (s.isBefore(e)) TimeInterval(s, e) else null
                }

                for (free in working.subtract(blocked)) {
                    slots += gridSlots(anchorInstant, free, rules.granularity, search.duration)
                }
            }
            date = date.plusDays(1)
        }
        return slots.sortedBy { it.start }
    }

    private fun gridSlots(
        anchor: Instant,
        free: TimeInterval,
        granularity: Duration,
        duration: Duration,
    ): List<TimeInterval> {
        val granMillis = granularity.toMillis()
        require(granMillis > 0) { "granularity must be positive" }
        val deltaMillis = Duration.between(anchor, free.start).toMillis()
        val k = if (deltaMillis <= 0) 0L else (deltaMillis + granMillis - 1) / granMillis
        var slotStart = anchor.plusMillis(k * granMillis)
        val out = mutableListOf<TimeInterval>()
        while (!slotStart.plus(duration).isAfter(free.end)) {
            out += TimeInterval(slotStart, slotStart.plus(duration))
            slotStart = slotStart.plusMillis(granMillis)
        }
        return out
    }
}
