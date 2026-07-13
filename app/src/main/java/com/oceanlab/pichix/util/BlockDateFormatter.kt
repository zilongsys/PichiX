package com.oceanlab.pichix.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Formato corto para log: «vie 15». */
object BlockDateFormatter {

    private val esShort = SimpleDateFormat("EEE d", Locale("es", "ES"))
    private val parsePatterns = listOf(
        "EEEE, MMMM d",
        "EEEE, MMM d",
        "EEE, MMM d",
        "MMMM d",
        "MMM d",
        "yyyy-MM-dd",
        "d MMM yyyy",
        "MMM d, yyyy",
    ).map { SimpleDateFormat(it, Locale.US) } +
        listOf(
            SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "ES")),
            SimpleDateFormat("EEE, d 'de' MMM", Locale("es", "ES")),
            SimpleDateFormat("d 'de' MMMM", Locale("es", "ES")),
        )

    fun formatShort(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        parsePatterns.forEach { fmt ->
            val pos = ParsePosition(0)
            val date = fmt.parse(trimmed, pos)
            if (date != null && pos.index > 0) {
                return esShort.format(date).lowercase(Locale("es", "ES"))
            }
        }
        val dayOnly = Regex("""\b(\d{1,2})\b""").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
        if (dayOnly != null) {
            val dow = Calendar.getInstance().getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale("es", "ES"))
            if (!dow.isNullOrBlank()) return "${dow.lowercase(Locale("es", "ES"))} $dayOnly"
        }
        return trimmed.take(16)
    }
}
