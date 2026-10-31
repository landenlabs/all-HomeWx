package com.landenlabs.homewx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HourlyRotationTest {

    private val hour = 60 * 60 * 1000L
    private val items = listOf("a", "b", "c")

    /** Starts at index [start] so tests don't depend on the random first pick. */
    private fun rotation(start: Int = 0) = HourlyRotation<String>(randomStart = { start })

    @Test fun emptyListGivesNull() = assertNull(rotation().pick("clear", emptyList<String>(), 0L))

    @Test fun firstPickUsesTheRandomStart() {
        assertEquals("c", rotation(start = 2).pick("clear", items, 0L))
    }

    @Test fun sameItemForTheWholeHour() {
        val r = rotation()
        assertEquals("a", r.pick("clear", items, 10 * hour))
        assertEquals("a", r.pick("clear", items, 10 * hour + 59 * 60_000L))
    }

    @Test fun nextItemEachNewHourThenWraps() {
        val r = rotation(start = 1)
        assertEquals("b", r.pick("clear", items, 10 * hour))
        assertEquals("c", r.pick("clear", items, 11 * hour))
        assertEquals("a", r.pick("clear", items, 12 * hour))
    }

    @Test fun severalHoursApartStillMovesOnlyOneStep() {
        val r = rotation(start = 0)
        assertEquals("a", r.pick("clear", items, 10 * hour))
        assertEquals("b", r.pick("clear", items, 15 * hour))
    }

    @Test fun conditionsRotateIndependently() {
        val r = rotation(start = 0)
        r.pick("clear", items, 10 * hour)
        assertEquals("b", r.pick("clear", items, 11 * hour))
        assertEquals("a", r.pick("rainy", items, 11 * hour)) // first pick for rainy, not advanced
    }

    @Test fun shrinkingListStaysInRange() {
        val r = rotation(start = 2)
        assertEquals("c", r.pick("clear", items, 10 * hour))
        assertEquals("a", r.pick("clear", listOf("a", "b"), 10 * hour)) // index 2 wraps into the shorter list
    }

    @Test fun singleImageAlwaysReturned() {
        val r = rotation()
        assertEquals("only", r.pick("clear", listOf("only"), 0L))
        assertEquals("only", r.pick("clear", listOf("only"), 5 * hour))
    }
}
