package com.oceanlab.pichix.analyzer

import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexBlockTypeFilter
import com.oceanlab.pichix.data.FlexPayCriteriaMode
import com.oceanlab.pichix.data.FlexTariffRule
import com.oceanlab.pichix.data.FlexTariffRulesStore
import java.util.Calendar

class FlexTariffEvaluator(private val settings: AppSettings) {

    fun evaluateListRow(offer: FlexBlockOffer, screenText: String = ""): FlexGrabResult =
        evaluateListRowDetailed(offer, screenText).result

    fun evaluateListRowDetailed(offer: FlexBlockOffer, screenText: String = ""): GrabEval {
        if (FlexTariffRulesStore.load(settings).none { it.enabled }) {
            return FlexGrabberEvaluator.evaluateListRowDetailed(offer, settings, screenText)
        }
        val pay = offer.payAmount ?: FlexGrabberEvaluator.parsePay(offer.payText)
            ?: return GrabEval.skip("Datos incompletos: sin pago en lista")
        val hourly = offer.hourlyRate ?: run {
            offer.durationHours?.takeIf { it > 0 }?.let { pay / it }
                ?: FlexGrabberEvaluator.hourlyFromPayAndTime(pay, offer.timeText, "")
        } ?: return GrabEval.skip("Datos incompletos: sin \$/h calculable")
        return evaluateInternalDetailed(
            station = offer.stationText,
            pay = pay,
            hourly = hourly,
            timeText = offer.timeText,
            durationHours = offer.durationHours
                ?: FlexGrabberEvaluator.resolveDurationHours(offer.timeText, ""),
            combinedText = "$screenText ${offer.stationText} ${offer.payText} ${offer.timeText}",
        )
    }

    fun evaluateDetailScreen(
        station: String,
        payRangeText: String,
        timeWindowText: String,
        screenText: String = "",
    ): FlexGrabResult = evaluateDetailScreenDetailed(station, payRangeText, timeWindowText, screenText).result

    fun evaluateDetailScreenDetailed(
        station: String,
        payRangeText: String,
        timeWindowText: String,
        screenText: String = "",
    ): GrabEval {
        if (FlexTariffRulesStore.load(settings).none { it.enabled }) {
            return FlexGrabberEvaluator.evaluateDetailScreenDetailed(
                payRangeText, timeWindowText, settings, station, screenText,
            )
        }
        val pay = FlexGrabberEvaluator.parsePay(payRangeText)
            ?: return GrabEval.skip("Detalle: sin pago legible")
        val hourly = FlexGrabberEvaluator.hourlyFromPayAndTime(pay, timeWindowText)
            ?: return GrabEval.skip("Detalle: sin \$/h calculable")
        return evaluateInternalDetailed(
            station = station,
            pay = pay,
            hourly = hourly,
            timeText = timeWindowText,
            durationHours = FlexGrabberEvaluator.parseDurationHours(timeWindowText),
            combinedText = "$screenText $station $payRangeText $timeWindowText",
        )
    }

    private fun evaluateInternalDetailed(
        station: String,
        pay: Double,
        hourly: Double,
        timeText: String,
        durationHours: Double?,
        combinedText: String,
    ): GrabEval {
        val active = FlexTariffRulesStore.load(settings).filter { it.enabled }.sortedBy { it.sortOrder }
        if (active.isEmpty()) {
            return GrabEval.reject("Sin reglas de tarifa activas")
        }
        val nowMinutes = currentMinutesOfDay()
        val lower = combinedText.lowercase()
        val detectedType = detectBlockType(lower)

        for (rule in active) {
            if (!FlexStationMatcher.matches(station, rule)) continue
            if (!matchesBlockType(detectedType, rule)) continue
            if (!matchesPay(pay, hourly, rule)) continue
            if (!matchesBlockStartWindow(timeText, rule)) continue
            if (!matchesLeadTime(timeText, rule)) continue
            if (!matchesDuration(durationHours, rule)) continue
            if (!matchesWeekday(rule)) continue
            if (!matchesPhoneSchedule(nowMinutes, rule)) continue
            val excluded = findExcludedKeyword(lower, rule.excludedKeywords)
            if (excluded != null) {
                return GrabEval.reject(
                    "Regla «${ruleLabel(rule)}»: palabra excluida «$excluded»",
                )
            }

            val label = ruleLabel(rule)
            val reason = "Regla «$label»: \$${"%.2f".format(pay)} · ${"%.2f".format(hourly)} \$/h"
            return if (settings.dryRunMode) GrabEval.simulated(reason) else GrabEval.accept(reason)
        }
        return GrabEval.reject("Ninguna regla de tarifa coincide")
    }

    private fun ruleLabel(rule: FlexTariffRule): String =
        rule.name.ifBlank { rule.stationPattern.ifBlank { "#${rule.sortOrder + 1}" } }

    private fun evaluateInternal(
        station: String,
        pay: Double,
        hourly: Double,
        timeText: String,
        durationHours: Double?,
        combinedText: String,
    ): FlexGrabResult {
        val active = FlexTariffRulesStore.load(settings).filter { it.enabled }.sortedBy { it.sortOrder }
        val nowMinutes = currentMinutesOfDay()
        val lower = combinedText.lowercase()
        val detectedType = detectBlockType(lower)

        for (rule in active) {
            if (!FlexStationMatcher.matches(station, rule)) continue
            if (!matchesBlockType(detectedType, rule)) continue
            if (!matchesPay(pay, hourly, rule)) continue
            if (!matchesBlockStartWindow(timeText, rule)) continue
            if (!matchesLeadTime(timeText, rule)) continue
            if (!matchesDuration(durationHours, rule)) continue
            if (!matchesWeekday(rule)) continue
            if (!matchesPhoneSchedule(nowMinutes, rule)) continue
            if (findExcludedKeyword(lower, rule.excludedKeywords) != null) {
                return FlexGrabResult.REJECT
            }

            return if (settings.dryRunMode) FlexGrabResult.SIMULATED_ACCEPT else FlexGrabResult.ACCEPT
        }
        return FlexGrabResult.REJECT
    }

    fun detectBlockType(lowerText: String): FlexBlockTypeFilter = when {
        lowerText.contains("whole foods") || lowerText.contains("wholefoods") ->
            FlexBlockTypeFilter.WHOLE_FOODS
        lowerText.contains("sub same day") || lowerText.contains("sub-same") ||
            lowerText.contains("same day delivery") ->
            FlexBlockTypeFilter.SUB_SAME_DAY
        lowerText.contains("same-day") || lowerText.contains("same day") ||
            lowerText.contains("sameday") ->
            FlexBlockTypeFilter.SUB_SAME_DAY
        lowerText.contains("amazon.com") || lowerText.contains("amazon com") ->
            FlexBlockTypeFilter.AMAZON_COM
        lowerText.contains("amazon") && !lowerText.contains("whole") ->
            FlexBlockTypeFilter.AMAZON_COM
        else -> FlexBlockTypeFilter.OTHER
    }

    private fun matchesBlockType(detected: FlexBlockTypeFilter, rule: FlexTariffRule): Boolean =
        when (rule.blockType) {
            FlexBlockTypeFilter.ALL -> true
            else -> rule.blockType == detected
        }

    private fun matchesPay(pay: Double, hourly: Double, rule: FlexTariffRule): Boolean {
        return when (rule.payMode) {
            FlexPayCriteriaMode.BLOCK_PAY -> {
                if (pay < rule.priceMin) return false
                if (rule.priceMax != null && pay > rule.priceMax) return false
                true
            }
            FlexPayCriteriaMode.HOURLY_PAY -> hourly >= rule.minHourlyRate
            FlexPayCriteriaMode.MANUAL_FIXED ->
                kotlin.math.abs(pay - rule.priceMin) < 0.51
            FlexPayCriteriaMode.MANUAL_ANY -> true
        }
    }

    private fun matchesBlockStartWindow(timeText: String, rule: FlexTariffRule): Boolean {
        if (!rule.blockStartFilterEnabled) return true
        val startMin = FlexGrabberEvaluator.parseStartMinutesOfDay(timeText) ?: return true
        val from = rule.blockStartFromMinutes
        val to = rule.blockStartToMinutes
        return if (from <= to) startMin in from..to else startMin >= from || startMin <= to
    }

    private fun matchesLeadTime(timeText: String, rule: FlexTariffRule): Boolean {
        val required = rule.minLeadTimeMinutes ?: return true
        val until = FlexGrabberEvaluator.minutesUntilBlockStart(timeText) ?: return true
        return until >= required
    }

    private fun matchesDuration(durationHours: Double?, rule: FlexTariffRule): Boolean {
        if (durationHours == null) return true
        rule.minDurationHours?.let { min ->
            if (durationHours < min) return false
        }
        rule.maxDurationHours?.let { max ->
            if (durationHours > max) return false
        }
        return true
    }

    private fun matchesWeekday(rule: FlexTariffRule): Boolean {
        if (!rule.weekdaysEnabled || rule.allowedWeekdays.isEmpty()) return true
        return Calendar.getInstance().get(Calendar.DAY_OF_WEEK) in rule.allowedWeekdays
    }

    private fun matchesPhoneSchedule(nowMinutes: Int, rule: FlexTariffRule): Boolean {
        if (!rule.timeEnabled) return true
        val start = rule.timeStartMinutes
        val end = rule.timeEndMinutes
        return if (start <= end) nowMinutes in start..end else nowMinutes >= start || nowMinutes <= end
    }

    private fun findExcludedKeyword(text: String, keywords: List<String>): String? {
        for (kw in keywords) {
            val k = kw.trim()
            if (k.isNotEmpty() && text.contains(k, ignoreCase = true)) return k
        }
        return null
    }

    private fun currentMinutesOfDay(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }
}
