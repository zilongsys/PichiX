package com.oceanlab.pichix.ui

import com.oceanlab.pichix.data.OfferLogEntry
import com.oceanlab.pichix.data.OfferStatus
import java.text.SimpleDateFormat
import java.util.Date

object OfferLogRowUi {

    fun statusIcon(status: OfferStatus): String = when (status) {
        OfferStatus.SIMULATED -> "🧪"
        OfferStatus.ACCEPTED -> "✅"
        OfferStatus.REJECTED -> "❌"
        OfferStatus.CANCELLED -> "🚫"
        OfferStatus.SEEN -> "👁"
        OfferStatus.MISS -> "💨"
    }

    fun timeWindowLabel(timeWindow: String): String {
        val t = timeWindow.trim()
        return if (t.isBlank()) "—" else t
    }

    fun metaRightLine(entry: OfferLogEntry, timeFmt: SimpleDateFormat): String {
        val hourly = if (entry.hourlyRate > 0.01) "${"%.2f".format(entry.hourlyRate)} \$/h" else "—"
        val timeStr = timeFmt.format(Date(entry.timestamp))
        return "$hourly · $timeStr"
    }

    fun reasonLeft(entry: OfferLogEntry): String {
        val raw = entry.reason.trim()
            .removePrefix("[SIMULACIÓN]")
            .removePrefix("[SIMULACION]")
            .trim()
        if (raw.isBlank()) return ""
        if (entry.status == OfferStatus.SEEN &&
            raw.equals("Vista en pantalla", ignoreCase = true)
        ) {
            return ""
        }
        val one = raw.split(Regex("\\s*·\\s*|\\s*;\\s*|\\s*\\|\\s*"))
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: raw
        return when (entry.status) {
            OfferStatus.ACCEPTED, OfferStatus.SIMULATED -> shortenAcceptReason(one)
            else -> one
        }
    }

    private fun shortenAcceptReason(reason: String): String {
        if (reason.contains("✓")) {
            val part = reason.substringAfter("✓", "").trim()
            if (part.isNotBlank()) return part
        }
        return reason
    }
}
