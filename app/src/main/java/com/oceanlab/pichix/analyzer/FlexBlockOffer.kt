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
    /** "2:15 PM", "5:30 PM - 9 PM" (toma la hora de inicio). */
    private val startWithMinutesRegex = Regex(
        """(\d{1,2})\s*:\s*(\d{2})\s*(AM|PM)?""",
        RegexOption.IGNORE_CASE,
    )
    /** "4 AM", "6 AM - 10 AM" (Flex a menudo omite :00). */
    private val startHourMeridiemRegex = Regex(
        """(\d{1,2})\s*(AM|PM)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parsePay(text: String): Double? =
        moneyRegex.find(text.replace(",", ""))?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * Minutos desde medianoche del **inicio** del bloque.
     * Acepta `2:15 PM`, `5:30 PM - 9 PM`, `4 AM`, `4 AM - 8 AM`.
     */
    fun parseStartMinutesOfDay(timeText: String): Int? {
        if (timeText.isBlank()) return null
        startWithMinutesRegex.find(timeText)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val mer = m.groupValues[3].ifBlank {
                meridiemNear(timeText, m.range.first)
            }
            return clockToMinutesOfDay(h, min, mer)
        }
        startHourMeridiemRegex.find(timeText)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val mer = m.groupValues[2]
            return clockToMinutesOfDay(h, 0, mer)
        }
        return null
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
        val mins = parseStartMinutesOfDay(timeText) ?: return null
        return mins / 60
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
        hourRangeRegex.find(timeText)?.let { range ->
            val h1 = range.groupValues[1].toIntOrNull() ?: return@let
            val m1 = range.groupValues[2].toIntOrNull() ?: 0
            val h2 = range.groupValues[4].toIntOrNull() ?: return@let
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
        // "4 AM - 8 AM" / "6 AM - 10:30 AM"
        val loose = Regex(
            """(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*[-–]\s*(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?""",
            RegexOption.IGNORE_CASE,
        ).find(timeText) ?: return null
        val h1 = loose.groupValues[1].toIntOrNull() ?: return null
        val m1 = loose.groupValues[2].toIntOrNull() ?: 0
        val h2 = loose.groupValues[4].toIntOrNull() ?: return null
        val m2 = loose.groupValues[5].toIntOrNull() ?: 0
        val startMer = loose.groupValues[3].ifBlank { meridiemNear(timeText, loose.range.first) }
        val endMer = loose.groupValues[6].ifBlank {
            meridiemNear(timeText, loose.range.last).ifBlank { startMer }
        }
        if (startMer.isBlank() && endMer.isBlank()) return null
        val startMin = clockToMinutesOfDay(h1, m1, startMer.ifBlank { endMer })
        var endMin = clockToMinutesOfDay(h2, m2, endMer.ifBlank { startMer })
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

    fun evaluateListRow(offer: FlexBlockOffer, settings: AppSettings, screenText: String = ""): FlexGrabResult =
        evaluateListRowDetailed(offer, settings, screenText).result

    fun evaluateListRowDetailed(
        offer: FlexBlockOffer,
        settings: AppSettings,
        screenText: String = "",
    ): GrabEval {
        if (settings.usesFlexDetailedTariff() && FlexTariffRulesStore.load(settings).any { it.enabled }) {
            return FlexTariffEvaluator(settings).evaluateListRowDetailed(offer, screenText)
        }
        val pay = offer.payAmount ?: parsePay(offer.payText)
            ?: return GrabEval.skip("Datos incompletos: sin pago en lista")
        if (pay < settings.flexMinBlockPay) {
            return GrabEval.reject(
                "Pago \$${"%.2f".format(pay)} < mínimo \$${"%.2f".format(settings.flexMinBlockPay)}",
            )
        }

        val startHour = offer.startHour ?: parseStartHour(offer.timeText)
        if (startHour != null && startHour < settings.flexMinStartHour) {
            return GrabEval.reject(
                "Inicio ${startHour}:00 < mínimo ${settings.flexMinStartHour}:00",
            )
        }

        val hourly = offer.hourlyRate
            ?: hourlyFromPayAndTime(pay, offer.timeText)
            ?: return GrabEval.skip("Datos incompletos: sin \$/h calculable")

        if (hourly < settings.flexMinHourlyRate) {
            return GrabEval.reject(
                "\$/h ${"%.2f".format(hourly)} < mínimo ${"%.2f".format(settings.flexMinHourlyRate)}",
            )
        }

        val reason = "Clásico: \$${"%.2f".format(pay)} · ${"%.2f".format(hourly)} \$/h"
        return if (settings.dryRunMode) GrabEval.simulated(reason) else GrabEval.accept(reason)
    }

    fun evaluateDetailScreen(
        payRangeText: String,
        timeWindowText: String,
        settings: AppSettings,
        station: String = "",
        screenText: String = "",
    ): FlexGrabResult = evaluateDetailScreenDetailed(
        payRangeText, timeWindowText, settings, station, screenText,
    ).result

    fun evaluateDetailScreenDetailed(
        payRangeText: String,
        timeWindowText: String,
        settings: AppSettings,
        station: String = "",
        screenText: String = "",
    ): GrabEval {
        if (settings.usesFlexDetailedTariff() && FlexTariffRulesStore.load(settings).any { it.enabled }) {
            return FlexTariffEvaluator(settings).evaluateDetailScreenDetailed(
                station, payRangeText, timeWindowText, screenText,
            )
        }
        val pay = parsePay(payRangeText)
            ?: return GrabEval.skip("Detalle: sin pago legible")
        if (pay < settings.flexMinBlockPay) {
            return GrabEval.reject(
                "Detalle pago \$${"%.2f".format(pay)} < mínimo \$${"%.2f".format(settings.flexMinBlockPay)}",
            )
        }
        val hourly = hourlyFromPayAndTime(pay, timeWindowText)
            ?: return GrabEval.skip("Detalle: sin \$/h calculable")
        if (hourly < settings.flexMinHourlyRate) {
            return GrabEval.reject(
                "Detalle \$/h ${"%.2f".format(hourly)} < mínimo ${"%.2f".format(settings.flexMinHourlyRate)}",
            )
        }
        val startHour = parseStartHour(timeWindowText)
        if (startHour != null && startHour < settings.flexMinStartHour) {
            return GrabEval.reject(
                "Detalle inicio ${startHour}:00 < mínimo ${settings.flexMinStartHour}:00",
            )
        }
        val reason = "Detalle OK: \$${"%.2f".format(pay)} · ${"%.2f".format(hourly)} \$/h"
        return if (settings.dryRunMode) GrabEval.simulated(reason) else GrabEval.accept(reason)
    }

    /**
     * Si el detalle no parsea bien pero la lista ya validó la oferta y el pago coincide,
     * reutiliza duración/\$/h de la lista (evita rechazos falsos en Offer Details).
     */
    fun evaluateDetailWithListFallback(
        offer: FlexBlockOffer,
        details: Map<String, String>,
        settings: AppSettings,
        station: String,
        screenText: String,
    ): GrabEval {
        val primary = evaluateDetailScreenDetailed(
            payRangeText = details["pay_range"].orEmpty(),
            timeWindowText = details["time_window"].orEmpty(),
            settings = settings,
            station = station,
            screenText = screenText,
        )
        if (primary.accepted) return primary

        if (settings.usesFlexDetailedTariff() &&
            FlexTariffRulesStore.load(settings).any { it.enabled }
        ) {
            return primary
        }

        val listPay = offer.payAmount ?: parsePay(offer.payText) ?: return primary
        val detailPay = parsePay(details["pay_range"].orEmpty()) ?: return primary
        if (kotlin.math.abs(listPay - detailPay) > 0.51) return primary

        val listEval = evaluateListRowDetailed(offer, settings, screenText)
        if (!listEval.accepted) return primary

        val hourly = offer.hourlyRate
            ?: hourlyFromPayAndTime(listPay, offer.timeText, offer.durationHours?.toString().orEmpty())
            ?: return primary
        if (hourly < settings.flexMinHourlyRate) return primary
        if (listPay < settings.flexMinBlockPay) return primary

        val startHour = offer.startHour ?: parseStartHour(offer.timeText)
        if (startHour != null && startHour < settings.flexMinStartHour) return primary

        val note = buildString {
            append("Detalle OK (lista): \$${"%.2f".format(listPay)} · ${"%.2f".format(hourly)} \$/h")
            if (primary.reason.isNotBlank()) {
                append(" · detalle: ")
                append(primary.reason)
            }
        }
        return if (settings.dryRunMode) GrabEval.simulated(note) else GrabEval.accept(note)
    }
}
