package com.oceanlab.pichix.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class OfferLogCsvStoreTest {

    private lateinit var saved: Locale

    @Before fun saveLocale() { saved = Locale.getDefault() }
    @After fun restoreLocale() { Locale.setDefault(saved) }

    private fun entry() = OfferLogEntry(
        price = 62.5,
        hourlyRate = 31.25,
        durationHours = 2.0,
        timeWindow = "4:30 AM - 6:30 AM",
        station = "DXX1, Test",
        status = OfferStatus.ACCEPTED,
        reason = "Regla «A»: \$62.50 · 31.25 \$/h, ok",
        timestamp = 1_700_000_000_000L,
        firstSeenAt = 1_699_999_999_000L,
        blockDate = "Mon, Nov 13",
        blockDateIso = "2026-11-13",
    )

    @Test
    fun formatLine_usesDotDecimalsRegardlessOfLocale() {
        Locale.setDefault(Locale("es", "ES"))
        val line = OfferLogCsvStore.formatLine(entry())
        val cols = line.split(",")
        assertEquals("62.50", cols[2])
        assertEquals("31.25", cols[3])
        assertEquals("2.0", cols[4])
        assertEquals(OfferLogCsvStore.COLUMN_COUNT, cols.size)
    }

    @Test
    fun roundTrip_underCommaLocale() {
        Locale.setDefault(Locale("de", "DE"))
        val e = entry()
        val back = OfferLogCsvStore.parseLine(OfferLogCsvStore.formatLine(e))
        assertNotNull(back)
        back!!
        assertEquals(e.timestamp, back.timestamp)
        assertEquals(62.5, back.price, 0.0)
        assertEquals(31.25, back.hourlyRate, 0.0)
        assertEquals(OfferStatus.ACCEPTED, back.status)
        assertEquals("DXX1; Test", back.station)
        assertEquals("2026-11-13", back.blockDateIso)
        assertEquals(e.firstSeenAt, back.firstSeenAt)
    }

    @Test
    fun parseLine_rejectsGarbage() {
        assertNull(OfferLogCsvStore.parseLine("no,es,csv"))
        assertNull(OfferLogCsvStore.parseLine("abc,2026-11-13,x,y,1.0,w,s,VISTA,r"))
    }

    @Test
    fun parseLine_acceptsLegacyStatusAndMissingTailColumns() {
        val e = OfferLogCsvStore.parseLine("1700000000000,2026-11-13 04:30:00,10.00,20.00,1.0,w,s,SI,r")
        assertNotNull(e)
        assertTrue(e!!.accepted)
        assertEquals(0L, e.firstSeenAt)
        assertEquals("", e.blockDateIso)
    }
}
