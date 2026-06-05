package com.oceanlab.pichix.analyzer

import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexTariffRulesStore
import com.oceanlab.pichix.util.BlockDurationText

data class FlexBlockOffer(
    val index: Int,
    val payText: String,
    val timeText: String,
    val stationText: String,
    val payAmount: Double?,
    val startHour: Int?,
    val durationHours: Double?,
    val hourlyRate: Double?,
)

enum class FlexGrabResult {
    ACCEPT,
    REJECT,
    SKIP,
    SIMULATED_ACCEPT,
}

object FlexGrabberEvaluator {

    private val moneyRegex = Regex("""\$\s*([\d,]+(?:\.\d{1,2})?)""")
    private val hourRangeRegex = Regex(
        """(\d{1,2})\s*:\s*(\d{2})\s*(AM|PM)?\s*[-–]\s*(\d{1,2})\s*:\s*(\d{2})\s*(AM|PM)?""",
        RegexOption.IGNORE_CASE,
    )
    private val hourOnlyRegex = Regex("""(\d{1,2})\s*:\s*(\d{2})""")

    fun parsePay(text: String): Double? =
        moneyRegex.find(text.replace(",", ""))?.groupValues?.get(1)?.toDoubleOrNull()

    fun parseStartMinutesOfDay(timeText: String): Int? {
        val m = hourOnlyRegex.find(timeText) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val isPm = timeText.contains("PM", ignoreCase = true)
        val isAm = timeText.contains("AM", ignoreCase = true)
        if (isPm && h < 12) h += 12
        if (isAm && h == 12) h = 0
        return h * 60 + min
    }

    fun minutesUntilBlockStart(timeText: String): Int? {
        val startMin = parseStartMinutesOfDay(timeText) ?: return null
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        var diff = startMin - nowMin
        if (diff < 0) diff += 24 * 60
        return diff
    }

    fun parseStartHour(timeText: String): Int? {
        val m = hourOnlyRegex.find(timeText) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val isPm = timeText.contains("PM", ignoreCase = true)
        val isAm = timeText.contains("AM", ignoreCase = true)
        if (isPm && h < 12) h += 12
        if (isAm && h == 12) h = 0
        return h
    }

    /** Duración en tarjeta de lista: "3 hr 30 min", "1.5 hr", "90 min", "3.30" (h.min). */
    fun parseDurationFromLabel(label: String): Double? {
        if (label.isBlank()) return null
        BlockDurationText.parseToHours(label)?.let { return it }
        val hrMin = Regex("""(\d+)\s*hr\s*(\d+)\s*min""", RegexOption.IGNORE_CASE).find(label)
        if (hrMin != null) {
            val h = hrMin.groupValues[1].toDoubleOrNull() ?: 0.0
            val m = hrMin.groupValues[2].toDoubleOrNull() ?: 0.0
            return h + m / 60.0
        }
        val minOnly = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(label)
        if (minOnly != null) {
            return (minOnly.groupValues[1].toDoubleOrNull() ?: return null) / 60.0
        }
        val hrOnly = Regex("""(\d+(?:\.\d+)?)\s*hrs?""", RegexOption.IGNORE_CASE).find(label)
        return hrOnly?.groupValues?.get(1)?.toDoubleOrNull()
    }

    fun parseDurationHours(timeText: String): Double? {
        parseDurationFromLabel(timeText)?.let { return it }
        val range = hourRangeRegex.find(timeText) ?: return null
        val h1 = range.groupValues[1].toIntOrNull() ?: return null
        val m1 = range.groupValues[2].toIntOrNull() ?: 0
        val h2 = range.groupValues[4].toIntOrNull() ?: return null
        val m2 = range.groupValues[5].toIntOrNull() ?: 0
        val startMer = range.groupValues[3].ifBlank { meridiemNear(timeText, range.range.first) }
        val endMer = range.groupValues[6].ifBlank { meridiemNear(timeText, range.range.last) ?: startMer }
        val startMin = clockToMinutesOfDay(h1, m1, startMer)
        var endMin = clockToMinutesOfDay(h2, m2, endMer)
        var diffMin = endMin - startMin
        if (diffMin <= 0) diffMin += 24 * 60
        val hours = diffMin / 60.0
        return if (hours in 0.25..10.0) hours else null
    }

    private fun meridiemNear(text: String, index: Int): String {
        val slice = text.substring(index.coerceAtLeast(0).coerceAtMost(text.length))
            .take(12)
            .uppercase()
        return when {
            slice.contains("PM") -> "PM"
            slice.contains("AM") -> "AM"
            else -> ""
        }
    }

    private fun clockToMinutesOfDay(hour: Int, minute: Int, meridiem: String): Int {
        var h = hour
        when (meridiem.uppercase()) {
            "PM" -> if (h < 12) h += 12
            "AM" -> if (h == 12) h = 0
        }
        return h * 60 + minute
    }

    /** Mejor estimación: etiqueta de duración primero, luego ventana horaria. */
    fun resolveDurationHours(timeText: String, durationLabel: String): Double? =
        parseDurationFromLabel(durationLabel)
            ?: parseDurationFromLabel(timeText)
            ?: parseDurationHours(timeText)

    fun hourlyFromPayAndTime(pay: Double, timeText: String, durationLabel: String = ""): Double? {
        val hours = parseDurationHours(timeText)
            ?: parseDurationFromLabel(durationLabel)
            ?: return null
        if (hours <= 0) return null
        return pay / hours
    }

    fun evaluateListRow(offer: FlexBlockOffer, settings: AppSettings, screenText: String = ""): FlexGrabResult {
        if (settings.usesFlexDetailedTariff() && FlexTariffRulesStore.load(settings).any { it.enabled }) {
            return FlexTariffEvaluator(settings).evaluateListRow(offer, screenText)
        }
        val pay = offer.payAmount ?: parsePay(offer.payText) ?: return FlexGrabResult.SKIP
        if (pay < settings.flexMinBlockPay) return FlexGrabResult.REJECT

        val startHour = offer.startHour ?: parseStartHour(offer.timeText)
        if (startHour != null && startHour < settings.flexMinStartHour) {
            return FlexGrabResult.REJECT
        }

        val hourly = offer.hourlyRate
            ?: hourlyFromPayAndTime(pay, offer.timeText)
            ?: return FlexGrabResult.SKIP

        if (hourly < settings.flexMinHourlyRate) return FlexGrabResult.REJECT

        return if (settings.dryRunMode) FlexGrabResult.SIMULATED_ACCEPT else FlexGrabResult.ACCEPT
    }

    fun evaluateDetailScreen(
        payRangeText: String,
        timeWindowText: String,
        settings: AppSettings,
        station: String = "",
        screenText: String = "",
    ): FlexGrabResult {
        if (settings.usesFlexDetailedTariff() && FlexTariffRulesStore.load(settings).any { it.enabled }) {
            return FlexTariffEvaluator(settings).evaluateDetailScreen(
                station, payRangeText, timeWindowText, screenText,
            )
        }
        val pay = parsePay(payRangeText) ?: return FlexGrabResult.SKIP
        if (pay < settings.flexMinBlockPay) return FlexGrabResult.REJECT
        val hourly = hourlyFromPayAndTime(pay, timeWindowText) ?: return FlexGrabResult.SKIP
        if (hourly < settings.flexMinHourlyRate) return FlexGrabResult.REJECT
        val startHour = parseStartHour(timeWindowText)
        if (startHour != null && startHour < settings.flexMinStartHour) {
            return FlexGrabResult.REJECT
        }
        return if (settings.dryRunMode) FlexGrabResult.SIMULATED_ACCEPT else FlexGrabResult.ACCEPT
    }
}
