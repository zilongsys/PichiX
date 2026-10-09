package com.oceanlab.pichix.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Formato corto para log: «vie 15». Resolución a yyyy-MM-dd para calendario. */
object BlockDateFormatter {

    private val esShort = SimpleDateFormat("EEE d", Locale("es", "ES"))
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayNumRegex = Regex("""\b(\d{1,2})\b""")

    private val parsePatterns = listOf(
        "EEEE, MMMM d",
        "EEEE, MMM d",
        "EEE, MMM d",
        "MMMM d",
        "MMM d",
        "yyyy-MM-dd",
        "d MMM yyyy",
        "MMM d, yyyy",
        "MMMM d, yyyy",
        "EEE d MMM",
        "EEE d MMMM",
    ).map { SimpleDateFormat(it, Locale.US) } +
        listOf(
            SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "ES")),
            SimpleDateFormat("EEE, d 'de' MMM", Locale("es", "ES")),
            SimpleDateFormat("d 'de' MMMM", Locale("es", "ES")),
            SimpleDateFormat("EEE d", Locale("es", "ES")),
            SimpleDateFormat("EEE d", Locale.US),
        )

    private val weekdayAliases = mapOf(
        "lun" to Calendar.MONDAY, "lunes" to Calendar.MONDAY, "mon" to Calendar.MONDAY, "monday" to Calendar.MONDAY,
        "mar" to Calendar.TUESDAY, "martes" to Calendar.TUESDAY, "tue" to Calendar.TUESDAY, "tuesday" to Calendar.TUESDAY,
        "mié" to Calendar.WEDNESDAY, "mie" to Calendar.WEDNESDAY, "miércoles" to Calendar.WEDNESDAY,
        "miercoles" to Calendar.WEDNESDAY, "wed" to Calendar.WEDNESDAY, "wednesday" to Calendar.WEDNESDAY,
        "jue" to Calendar.THURSDAY, "jueves" to Calendar.THURSDAY, "thu" to Calendar.THURSDAY, "thursday" to Calendar.THURSDAY,
        "vie" to Calendar.FRIDAY, "viernes" to Calendar.FRIDAY, "fri" to Calendar.FRIDAY, "friday" to Calendar.FRIDAY,
        "sáb" to Calendar.SATURDAY, "sab" to Calendar.SATURDAY, "sábado" to Calendar.SATURDAY,
        "sabado" to Calendar.SATURDAY, "sat" to Calendar.SATURDAY, "saturday" to Calendar.SATURDAY,
        "dom" to Calendar.SUNDAY, "domingo" to Calendar.SUNDAY, "sun" to Calendar.SUNDAY, "sunday" to Calendar.SUNDAY,
    )

    fun formatShort(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        parseToCalendar(trimmed, System.currentTimeMillis())?.let { cal ->
            return esShort.format(cal.time).lowercase(Locale("es", "ES"))
        }
        val dayOnly = dayNumRegex.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
        if (dayOnly != null) {
            val dow = Calendar.getInstance()
                .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale("es", "ES"))
            if (!dow.isNullOrBlank()) return "${dow.lowercase(Locale("es", "ES"))} $dayOnly"
        }
        return trimmed.take(16)
    }

    /**
     * ISO `yyyy-MM-dd` del día del **bloque** (no del take).
     * [rawDate] = texto Flex de fecha; [shortOrBlank] = «vie 15»; [refMs] = instante del take/log.
     */
    fun resolveIso(rawDate: String, shortOrBlank: String = "", refMs: Long = System.currentTimeMillis()): String {
        val raw = rawDate.trim()
        if (raw.isNotBlank()) {
            parseToCalendar(raw, refMs)?.let { return isoFmt.format(it.time) }
        }
        val short = shortOrBlank.trim().ifBlank { formatShort(raw) }
        if (short.isNotBlank()) {
            resolveFromShort(short, refMs)?.let { return isoFmt.format(it.time) }
        }
        return ""
    }

    private fun parseToCalendar(raw: String, refMs: Long): Calendar? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.US)
        if (lower == "today" || lower == "hoy") {
            return Calendar.getInstance().apply { timeInMillis = refMs; clearTime() }
        }
        if (lower == "tomorrow" || lower == "mañana" || lower == "manana") {
            return Calendar.getInstance().apply {
                timeInMillis = refMs
                clearTime()
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        parsePatterns.forEach { fmt ->
            val pos = ParsePosition(0)
            val date = fmt.parse(trimmed, pos)
            if (date != null && pos.index > 0) {
                val cal = Calendar.getInstance().apply { time = date }
                // Si el patrón no trae año (año epoch ~1970), anclar al año de ref / próximo.
                if (cal.get(Calendar.YEAR) < 2000) {
                    anchorYearNearRef(cal, refMs)
                }
                return cal
            }
        }
        return resolveFromShort(trimmed, refMs)
    }

    private fun resolveFromShort(short: String, refMs: Long): Calendar? {
        val day = dayNumRegex.find(short)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (day !in 1..31) return null
        val token = short.trim().lowercase(Locale("es", "ES"))
            .replace(".", "")
            .split(Regex("""\s+"""))
            .firstOrNull()
            ?.take(3)
        val wantDow = token?.let { weekdayAliases[it] }

        val ref = Calendar.getInstance().apply { timeInMillis = refMs; clearTime() }
        // Preferir el día del bloque en el futuro cercano respecto al take (hasta +45 días),
        // luego pasado cercano (−7). Cubre «tomé el lunes para el miércoles».
        var best: Calendar? = null
        var bestScore = Long.MAX_VALUE
        for (deltaMonth in -1..2) {
            val cand = Calendar.getInstance().apply {
                timeInMillis = ref.timeInMillis
                add(Calendar.MONTH, deltaMonth)
                val max = getActualMaximum(Calendar.DAY_OF_MONTH)
                if (day > max) return@apply
                set(Calendar.DAY_OF_MONTH, day)
                clearTime()
            }
            if (cand.get(Calendar.DAY_OF_MONTH) != day) continue
            if (wantDow != null && cand.get(Calendar.DAY_OF_WEEK) != wantDow) continue
            val diff = cand.timeInMillis - ref.timeInMillis
            val score = when {
                diff in 0..(45L * 24 * 60 * 60 * 1000) -> diff
                diff < 0 && diff >= -7L * 24 * 60 * 60 * 1000 -> -diff + 50L * 24 * 60 * 60 * 1000
                else -> continue
            }
            if (score < bestScore) {
                bestScore = score
                best = cand
            }
        }
        return best
    }

    private fun anchorYearNearRef(cal: Calendar, refMs: Long) {
        val ref = Calendar.getInstance().apply { timeInMillis = refMs }
        cal.set(Calendar.YEAR, ref.get(Calendar.YEAR))
        // Si quedó >45 días en el pasado respecto al take, probar año siguiente.
        if (cal.timeInMillis < refMs - 45L * 24 * 60 * 60 * 1000) {
            cal.add(Calendar.YEAR, 1)
        }
        // Si quedó >300 días en el futuro, año anterior.
        if (cal.timeInMillis > refMs + 300L * 24 * 60 * 60 * 1000) {
            cal.add(Calendar.YEAR, -1)
        }
    }

    private fun Calendar.clearTime() {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
