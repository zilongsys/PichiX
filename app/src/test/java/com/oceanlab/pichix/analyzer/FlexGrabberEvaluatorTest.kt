package com.oceanlab.pichix.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlexGrabberEvaluatorTest {

    @Test
    fun parsePay_handlesThousandsAndCents() {
        assertEquals(62.5, FlexGrabberEvaluator.parsePay("Pay \$62.50")!!, 0.0)
        assertEquals(1234.5, FlexGrabberEvaluator.parsePay("\$1,234.50 total")!!, 0.0)
        assertEquals(90.0, FlexGrabberEvaluator.parsePay("\$ 90")!!, 0.0)
        assertNull(FlexGrabberEvaluator.parsePay("sin dinero"))
    }

    @Test
    fun parseStartMinutes_variousFlexFormats() {
        assertEquals(14 * 60 + 15, FlexGrabberEvaluator.parseStartMinutesOfDay("2:15 PM"))
        assertEquals(17 * 60 + 30, FlexGrabberEvaluator.parseStartMinutesOfDay("5:30 PM - 9 PM"))
        assertEquals(4 * 60, FlexGrabberEvaluator.parseStartMinutesOfDay("4 AM"))
        assertEquals(4 * 60, FlexGrabberEvaluator.parseStartMinutesOfDay("4 AM - 8 AM"))
    }

    @Test
    fun parseStartMinutes_noonAndMidnight() {
        assertEquals(0, FlexGrabberEvaluator.parseStartMinutesOfDay("12:00 AM"))
        assertEquals(12 * 60, FlexGrabberEvaluator.parseStartMinutesOfDay("12:00 PM"))
        assertEquals(12 * 60 + 30, FlexGrabberEvaluator.parseStartMinutesOfDay("12:30 PM"))
    }

    @Test
    fun parseStartMinutes_unreadableIsNull() {
        assertNull(FlexGrabberEvaluator.parseStartMinutesOfDay(""))
        assertNull(FlexGrabberEvaluator.parseStartMinutesOfDay("mañana"))
    }

    @Test
    fun parseStartHour_isFloorOfMinutes() {
        assertEquals(14, FlexGrabberEvaluator.parseStartHour("2:15 PM"))
    }

    @Test
    fun parseDurationFromLabel_formats() {
        assertEquals(3.5, FlexGrabberEvaluator.parseDurationFromLabel("3 hr 30 min")!!, 1e-9)
        assertEquals(1.5, FlexGrabberEvaluator.parseDurationFromLabel("90 min")!!, 1e-9)
        assertEquals(1.5, FlexGrabberEvaluator.parseDurationFromLabel("1.5 hr")!!, 1e-9)
        assertNull(FlexGrabberEvaluator.parseDurationFromLabel(""))
    }

    @Test
    fun parseDurationHours_fromTimeRange() {
        assertEquals(2.0, FlexGrabberEvaluator.parseDurationHours("4:30 AM - 6:30 AM")!!, 1e-9)
        assertEquals(4.0, FlexGrabberEvaluator.parseDurationHours("4 AM - 8 AM")!!, 1e-9)
        // cruza el mediodía
        assertEquals(4.0, FlexGrabberEvaluator.parseDurationHours("10:00 AM - 2:00 PM")!!, 1e-9)
    }
}
