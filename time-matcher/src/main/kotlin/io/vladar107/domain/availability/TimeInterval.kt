package io.vladar107.domain.availability

import java.time.Duration
import java.time.Instant

/** Half-open interval [start, end) on the absolute timeline. */
data class TimeInterval(val start: Instant, val end: Instant) {
    init { require(start.isBefore(end)) { "start must be before end: $start..$end" } }

    val duration: Duration get() = Duration.between(start, end)

    fun overlaps(other: TimeInterval): Boolean =
        start.isBefore(other.end) && other.start.isBefore(end)

    /** This interval with [block] removed; 0, 1, or 2 remaining pieces. */
    internal fun minus(block: TimeInterval): List<TimeInterval> {
        if (!overlaps(block)) return listOf(this)
        val pieces = mutableListOf<TimeInterval>()
        if (start.isBefore(block.start)) pieces += TimeInterval(start, minOf(end, block.start))
        if (block.end.isBefore(end)) pieces += TimeInterval(maxOf(start, block.end), end)
        return pieces
    }
}

/** Coalesce overlapping or touching intervals into a minimal, sorted list. */
fun List<TimeInterval>.merged(): List<TimeInterval> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.start }
    val result = mutableListOf(sorted.first())
    for (current in sorted.drop(1)) {
        val last = result.last()
        if (!current.start.isAfter(last.end)) {
            if (current.end.isAfter(last.end)) {
                result[result.lastIndex] = TimeInterval(last.start, current.end)
            }
        } else {
            result += current
        }
    }
    return result
}

/** Subtract every blocked interval from every interval in the receiver. */
fun List<TimeInterval>.subtract(blocked: List<TimeInterval>): List<TimeInterval> {
    val merged = blocked.merged()
    return flatMap { interval ->
        merged.fold(listOf(interval)) { pieces, block ->
            pieces.flatMap { it.minus(block) }
        }
    }
}
