package io.vladar107.domain.availability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeIntervalTest {
    private fun t(s: String): Instant = Instant.parse(s)
    private fun interval(a: String, b: String) = TimeInterval(t(a), t(b))

    @Test
    fun mergesOverlappingAndTouchingIntervals() {
        val merged = listOf(
            interval("2030-01-01T09:00:00Z", "2030-01-01T10:00:00Z"),
            interval("2030-01-01T09:30:00Z", "2030-01-01T10:30:00Z"), // overlaps
            interval("2030-01-01T10:30:00Z", "2030-01-01T11:00:00Z"), // touches
            interval("2030-01-01T12:00:00Z", "2030-01-01T13:00:00Z"), // separate
        ).merged()

        assertEquals(
            listOf(
                interval("2030-01-01T09:00:00Z", "2030-01-01T11:00:00Z"),
                interval("2030-01-01T12:00:00Z", "2030-01-01T13:00:00Z"),
            ),
            merged,
        )
    }

    @Test
    fun subtractSplitsAnIntervalAroundABlock() {
        val free = listOf(interval("2030-01-01T09:00:00Z", "2030-01-01T17:00:00Z"))
        val blocked = listOf(interval("2030-01-01T12:00:00Z", "2030-01-01T13:00:00Z"))

        assertEquals(
            listOf(
                interval("2030-01-01T09:00:00Z", "2030-01-01T12:00:00Z"),
                interval("2030-01-01T13:00:00Z", "2030-01-01T17:00:00Z"),
            ),
            free.subtract(blocked),
        )
    }

    @Test
    fun subtractRemovesFullyCoveredInterval() {
        val free = listOf(interval("2030-01-01T12:00:00Z", "2030-01-01T13:00:00Z"))
        val blocked = listOf(interval("2030-01-01T11:00:00Z", "2030-01-01T14:00:00Z"))
        assertEquals(emptyList(), free.subtract(blocked))
    }

    @Test
    fun subtractWithNoOverlapKeepsIntervalWhole() {
        val free = listOf(interval("2030-01-01T09:00:00Z", "2030-01-01T10:00:00Z"))
        val blocked = listOf(interval("2030-01-01T11:00:00Z", "2030-01-01T12:00:00Z"))
        assertEquals(free, free.subtract(blocked))
    }
}
