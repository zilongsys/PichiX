package com.oceanlab.pichix.util

import kotlin.math.roundToInt

/**
 * Duración de bloque en texto h.min: horas + minutos tras el punto (ej. 3.30 = 3 h 30 min).
 * También acepta h:mm. Vacío = sin límite (cualquier duración).
 */
object BlockDurationText {

    fun parseToHours(text: String?): Double? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.contains(':')) {
            val parts = raw.split(':', limit = 2)
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h < 0 || m !in 0..59) return null
            return h + m / 60.0
        }
        if (raw.contains('.')) {
            val parts = raw.split('.', limit = 2)
            val h = parts[0].toIntOrNull() ?: return null
            if (h < 0) return null
            if (parts.size == 1) return h.toDouble()
            val minPart = parts[1]
            if (minPart.isEmpty()) return h.toDouble()
            val m = minPart.toIntOrNull() ?: return null
            if (m !in 0..59) return null
            return h + m / 60.0
        }
        val whole = raw.toIntOrNull() ?: return null
        if (whole < 0) return null
        return whole.toDouble()
    }

    fun formatFromHours(hours: Double): String {
        val totalMin = (hours * 60.0).roundToInt().coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (m == 0) h.toString() else "%d.%02d".format(h, m)
    }
}
