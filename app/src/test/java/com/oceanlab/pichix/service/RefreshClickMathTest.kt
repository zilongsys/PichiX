package com.oceanlab.pichix.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshClickMathTest {

    @Test
    fun threshold_roundsDownAndAtLeastOne() {
        assertEquals(8, RefreshClickMath.threshold(10, 80))
        assertEquals(1, RefreshClickMath.threshold(1, 50))
        assertEquals(10, RefreshClickMath.threshold(10, 100))
    }

    @Test
    fun orderedPercents_keepsWarnBeforePause() {
        assertEquals(80 to 92, RefreshClickMath.orderedPercents(80, 92))
        assertEquals(91 to 92, RefreshClickMath.orderedPercents(99, 92))
        assertEquals(50 to 50, RefreshClickMath.orderedPercents(50, 50))
    }

    @Test
    fun median_oddAndEven() {
        assertEquals(5L, RefreshClickMath.median(listOf(1, 5, 9)))
        assertEquals(6L, RefreshClickMath.median(listOf(2, 4, 8, 10)))
        assertEquals(0L, RefreshClickMath.median(emptyList()))
    }

    @Test
    fun learnedLimit_usesLastFive() {
        val counts = listOf(100, 100, 10, 12, 11, 13, 14)
        assertEquals(12, RefreshClickMath.learnedLimit(counts))
        assertNull(RefreshClickMath.learnedLimit(emptyList()))
    }

    @Test
    fun decide_pauseBeatsWarnWhenBothCrossed() {
        val d = RefreshClickMath.decide(
            current = 10,
            limit = 10,
            warnPct = 80,
            pausePct = 92,
            warnEnabled = true,
            pauseEnabled = true,
            alreadyWarned = false,
            alreadyPaused = false,
        )
        assertEquals(RefreshClickMath.Decision.PAUSE, d)
    }

    @Test
    fun decide_warnOnceThenNoneUntilPause() {
        val warn = RefreshClickMath.decide(8, 10, 80, 92, true, true, alreadyWarned = false, alreadyPaused = false)
        assertEquals(RefreshClickMath.Decision.WARN, warn)
        val none = RefreshClickMath.decide(8, 10, 80, 92, true, true, alreadyWarned = true, alreadyPaused = false)
        assertEquals(RefreshClickMath.Decision.NONE, none)
        val pause = RefreshClickMath.decide(10, 10, 80, 92, true, true, alreadyWarned = true, alreadyPaused = false)
        assertEquals(RefreshClickMath.Decision.PAUSE, pause)
    }

    @Test
    fun decide_respectsDisabledFlags() {
        val none = RefreshClickMath.decide(10, 10, 80, 92, warnEnabled = false, pauseEnabled = false, alreadyWarned = false, alreadyPaused = false)
        assertEquals(RefreshClickMath.Decision.NONE, none)
    }

    @Test
    fun shouldDedupBanner_sixtySeconds() {
        assertTrue(RefreshClickMath.shouldDedupBanner(1_000L, 30_000L))
        assertFalse(RefreshClickMath.shouldDedupBanner(1_000L, 70_000L))
        assertFalse(RefreshClickMath.shouldDedupBanner(0L, 10_000L))
    }

    @Test
    fun snapshot_emptyIsNull() {
        assertNull(RefreshClickMath.snapshot(emptyList(), 0L, 0L, live = true, endedAtMs = 0L, fallbackBurst = false))
    }

    @Test
    fun snapshot_countsBurstAndGaps() {
        val clicks = listOf(
            RefreshClickMath.Click(1_000L, false),
            RefreshClickMath.Click(2_000L, true),
            RefreshClickMath.Click(13_000L, false),
        )
        val snap = RefreshClickMath.snapshot(
            clicks = clicks,
            nowMs = 13_000L,
            lastBannerAtMs = 0L,
            live = false,
            endedAtMs = 13_000L,
            fallbackBurst = false,
        )!!
        assertEquals(3, snap.clicks)
        assertEquals(1, snap.burstClicks)
        assertEquals(2, snap.normalClicks)
        assertEquals(12_000L, snap.elapsedMs)
        assertEquals(12_000L, snap.clickingMs)
        assertEquals(12_000L, snap.activeClickingMs)
        assertEquals(15.0, snap.clicksPerMin, 0.01)
        assertEquals(1_000L, snap.minGapMs)
        assertEquals(11_000L, snap.maxGapMs)
        assertEquals(6_000L, snap.avgGapMs)
        assertEquals(0, snap.idleGaps)
        assertTrue(snap.burst)
    }

    @Test
    fun snapshot_liveFreezesAtLastClick() {
        val clicks = listOf(RefreshClickMath.Click(1_000L, false))
        val snap = RefreshClickMath.snapshot(clicks, nowMs = 61_000L, lastBannerAtMs = 0L, live = true, endedAtMs = 0L, fallbackBurst = false)!!
        assertEquals(0L, snap.elapsedMs)
        assertEquals(0L, snap.clickingMs)
        assertEquals(0.0, snap.clicksPerMin, 0.0)
        assertEquals(1, snap.clicksLast60s)
    }

    @Test
    fun pace_ignoresIdleGapsAndWallClock() {
        val clicks = listOf(
            RefreshClickMath.Click(1_000L, false),
            RefreshClickMath.Click(2_000L, false),
            RefreshClickMath.Click(1_000L + RefreshClickMath.IDLE_RESET_MS + 5_000L, false),
            RefreshClickMath.Click(1_000L + RefreshClickMath.IDLE_RESET_MS + 6_000L, false),
        )
        val later = clicks.last().atMs + 120_000L
        val snap = RefreshClickMath.snapshot(clicks, nowMs = later, lastBannerAtMs = 0L, live = true, endedAtMs = 0L, fallbackBurst = false)!!
        assertEquals(2_000L, snap.activeClickingMs)
        assertEquals(4 * 60_000.0 / 2_000.0, snap.clicksPerMin, 0.01)
        assertEquals(1, snap.idleGaps)
    }

    @Test
    fun snapshot_waitAfterPreviousBanner() {
        val clicks = listOf(RefreshClickMath.Click(20_000L, false), RefreshClickMath.Click(25_000L, false))
        val snap = RefreshClickMath.snapshot(clicks, nowMs = 25_000L, lastBannerAtMs = 5_000L, live = false, endedAtMs = 25_000L, fallbackBurst = false)!!
        assertEquals(15_000L, snap.waitAfterPreviousMs)
        assertEquals(20_000L, snap.cycleMs)
    }

    @Test
    fun countSince_fromNewest() {
        val clicks = (1..5).map { RefreshClickMath.Click(it * 1_000L, false) }
        assertEquals(3, RefreshClickMath.countSince(clicks, 3_000L))
        assertEquals(5, RefreshClickMath.countSince(clicks, 0L))
        assertEquals(0, RefreshClickMath.countSince(clicks, 9_000L))
    }

    @Test
    fun formatDuration_units() {
        assertEquals("5s", RefreshClickMath.formatDuration(5_000L))
        assertEquals("1m 5s", RefreshClickMath.formatDuration(65_000L))
        assertEquals("1h 1m", RefreshClickMath.formatDuration(3_660_000L))
    }
}
