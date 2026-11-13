package com.oceanlab.pichix.analyzer

import com.oceanlab.pichix.data.FlexPayCriteriaMode
import com.oceanlab.pichix.data.FlexTariffRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffMathTest {

    private fun rule(
        mode: FlexPayCriteriaMode,
        priceMin: Double = 90.0,
        priceMax: Double? = null,
        minHourly: Double = 23.0,
    ) = FlexTariffRule(payMode = mode, priceMin = priceMin, priceMax = priceMax, minHourlyRate = minHourly)

    @Test
    fun blockPay_rangeInclusive() {
        val r = rule(FlexPayCriteriaMode.BLOCK_PAY, priceMin = 90.0, priceMax = 120.0)
        assertTrue(TariffMath.matchesPay(90.0, 0.0, r))
        assertTrue(TariffMath.matchesPay(120.0, 0.0, r))
        assertFalse(TariffMath.matchesPay(89.99, 99.0, r))
        assertFalse(TariffMath.matchesPay(120.01, 99.0, r))
    }

    @Test
    fun blockPay_noMaxMeansUnbounded() {
        val r = rule(FlexPayCriteriaMode.BLOCK_PAY, priceMin = 90.0)
        assertTrue(TariffMath.matchesPay(10_000.0, 0.0, r))
    }

    @Test
    fun hourlyPay_ignoresBlockPrice() {
        val r = rule(FlexPayCriteriaMode.HOURLY_PAY, minHourly = 23.0, priceMin = 500.0)
        assertTrue(TariffMath.matchesPay(10.0, 23.0, r))
        assertFalse(TariffMath.matchesPay(1000.0, 22.99, r))
    }

    @Test
    fun hourlyAndBlock_needsBoth() {
        val r = rule(FlexPayCriteriaMode.HOURLY_AND_BLOCK, priceMin = 90.0, priceMax = 150.0, minHourly = 25.0)
        assertTrue(TariffMath.matchesPay(100.0, 25.0, r))
        assertFalse(TariffMath.matchesPay(100.0, 24.9, r))
        assertFalse(TariffMath.matchesPay(80.0, 30.0, r))
        assertFalse(TariffMath.matchesPay(160.0, 30.0, r))
    }

    @Test
    fun manualFixed_toleranceHalfDollar() {
        val r = rule(FlexPayCriteriaMode.MANUAL_FIXED, priceMin = 100.0)
        assertTrue(TariffMath.matchesPay(100.5, 0.0, r))
        assertTrue(TariffMath.matchesPay(99.5, 0.0, r))
        assertFalse(TariffMath.matchesPay(100.51, 0.0, r))
    }

    @Test
    fun manualAny_alwaysMatches() {
        assertTrue(TariffMath.matchesPay(0.0, 0.0, rule(FlexPayCriteriaMode.MANUAL_ANY)))
    }

    @Test
    fun duration_unknownAlwaysPasses_limitsInclusive() {
        val r = FlexTariffRule(minDurationHours = 2.0, maxDurationHours = 4.0)
        assertTrue(TariffMath.matchesDuration(null, r))
        assertTrue(TariffMath.matchesDuration(2.0, r))
        assertTrue(TariffMath.matchesDuration(4.0, r))
        assertFalse(TariffMath.matchesDuration(1.99, r))
        assertFalse(TariffMath.matchesDuration(4.01, r))
        assertTrue(TariffMath.matchesDuration(99.0, FlexTariffRule()))
    }

    @Test
    fun window_normalAndOvernight() {
        assertTrue(TariffMath.inWindow(6 * 60, 22 * 60, 6 * 60))
        assertTrue(TariffMath.inWindow(6 * 60, 22 * 60, 22 * 60))
        assertFalse(TariffMath.inWindow(6 * 60, 22 * 60, 5 * 60 + 59))
        // 22:00 → 06:00 cruza medianoche
        assertTrue(TariffMath.inWindow(22 * 60, 6 * 60, 23 * 60))
        assertTrue(TariffMath.inWindow(22 * 60, 6 * 60, 3 * 60))
        assertFalse(TariffMath.inWindow(22 * 60, 6 * 60, 12 * 60))
    }

    @Test
    fun weekday_disabledOrEmptyAllowsAll() {
        assertTrue(TariffMath.weekdayAllowed(FlexTariffRule(), 3))
        assertTrue(TariffMath.weekdayAllowed(FlexTariffRule(weekdaysEnabled = true, allowedWeekdays = emptySet()), 3))
    }

    @Test
    fun weekday_enabledFiltersByCalendarDay() {
        val r = FlexTariffRule(weekdaysEnabled = true, allowedWeekdays = setOf(2, 6)) // lun, vie
        assertTrue(TariffMath.weekdayAllowed(r, 2))
        assertEquals(false, TariffMath.weekdayAllowed(r, 1))
    }
}
