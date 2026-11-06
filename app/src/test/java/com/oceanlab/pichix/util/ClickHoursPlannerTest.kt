package com.oceanlab.pichix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ClickHoursPlannerTest {

    @Test
    fun fewAccepts_alwaysClick() {
        val now = ts(2026, Calendar.NOVEMBER, 6, 12, 0)
        val plan = ClickHoursPlanner.compute(
            acceptAtMs = listOf(ts(2026, Calendar.NOVEMBER, 5, 4, 10)),
            nowMs = now,
            params = ClickHoursPlanner.Params(minAccepts = 10),
        )
        assertTrue(plan.alwaysClick)
        assertEquals("few_data", plan.reason)
        assertTrue(plan.contains(now))
        assertEquals(0L, plan.millisUntilOpen(now))
    }

    @Test
    fun peakHour_getsMarginAndContains() {
        val accepts = (1..12).map { day ->
            ts(2026, Calendar.OCTOBER, day, 4, 15)
        }
        val now = ts(2026, Calendar.OCTOBER, 15, 4, 20)
        val plan = ClickHoursPlanner.compute(
            acceptAtMs = accepts,
            nowMs = now,
            params = ClickHoursPlanner.Params(marginMin = 30, ofBestPct = 50, minAccepts = 10),
        )
        assertFalse(plan.alwaysClick)
        assertTrue(plan.contains(ts(2026, Calendar.OCTOBER, 15, 3, 50)))
        assertTrue(plan.contains(ts(2026, Calendar.OCTOBER, 15, 4, 40)))
        assertFalse(plan.contains(ts(2026, Calendar.OCTOBER, 15, 12, 0)))
    }

    @Test
    fun weakHour_excludedWhenBelowPct() {
        val week = 7L * 24 * 60 * 60 * 1000
        val monday4 = ts(2026, Calendar.OCTOBER, 5, 4, 0)
        val peak = (0..3).map { monday4 + it * week }
        val weak = listOf(ts(2026, Calendar.OCTOBER, 5, 15, 0))
        val now = ts(2026, Calendar.OCTOBER, 26, 15, 10)
        val plan = ClickHoursPlanner.compute(
            acceptAtMs = peak + weak,
            nowMs = now,
            params = ClickHoursPlanner.Params(marginMin = 0, ofBestPct = 50, minAccepts = 4),
        )
        assertFalse(plan.contains(now))
        assertTrue(plan.contains(ts(2026, Calendar.OCTOBER, 26, 4, 10)))
    }

    @Test
    fun wrapMidnight_merges() {
        val mark = ClickHoursPlanner.expandHours(setOf(0, 23), marginMin = 30)
        val windows = ClickHoursPlanner.mergeMarkedMinutes(mark)
        assertEquals(1, windows.size)
        assertTrue(windows[0].contains(23 * 60 + 50))
        assertTrue(windows[0].contains(10))
        assertFalse(windows[0].contains(12 * 60))
    }

    @Test
    fun idleDelay_caps() {
        assertEquals(0L, ClickHoursPlanner.idleDelayMs(0L))
        assertEquals(5_000L, ClickHoursPlanner.idleDelayMs(1_000L))
        assertEquals(30_000L, ClickHoursPlanner.idleDelayMs(3_600_000L))
        assertEquals(12_000L, ClickHoursPlanner.idleDelayMs(12_000L))
    }

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
