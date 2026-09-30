package com.oceanlab.pichix.data

import com.oceanlab.pichix.analyzer.FlexGrabberEvaluator
import com.oceanlab.pichix.analyzer.FlexStationMatcher
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

enum class NotTakenCause(val label: String, val tunable: Boolean = false) {
    NO_RULE_MATCH("Ninguna regla coincide"),
    STATION_UNCOVERED("Estación sin regla"),
    HOURLY_MIN("$/h bajo el mínimo", tunable = true),
    BLOCK_PAY_MIN("Pago de bloque bajo el mínimo", tunable = true),
    BLOCK_PAY_MAX("Pago de bloque sobre el máximo", tunable = true),
    BLOCK_START_WINDOW("Inicio de bloque fuera de ventana"),
    LEAD_TIME("Lead time insuficiente", tunable = true),
    DURATION("Duración fuera de rango", tunable = true),
    WEEKDAY("Día no habilitado"),
    PHONE_SCHEDULE("Fuera de horario del teléfono"),
    BLOCK_TYPE("Tipo de bloque no habilitado"),
    KEYWORD_EXCLUDED("Keyword excluido"),
    INCOMPLETE_DATA("Datos incompletos"),
    DETAIL_MISMATCH("Lista ≠ detalle"),
    TAKE_MISS_UNAVAILABLE("Bloque no disponible / reserved"),
    TAKE_MISS_NO_CONFIRM("Sin confirmación tras Schedule"),
    TAKE_MISS_OTHER("Otra pérdida al tomar"),
    PASSED_NOT_TAKEN("Cumplía pero no se tomó"),
    OTHER("Otro motivo"),
}

/** Condición que falló, con valor de la oferta ([found]) y límite de la regla ([limit]). */
data class NotTakenDiagnosis(
    val cause: NotTakenCause,
    val ruleNumber: Int? = null,
    val ruleTitle: String = "",
    val found: Double? = null,
    val limit: Double? = null,
) {
    val relativeGap: Double?
        get() {
            val f = found ?: return null
            val l = limit ?: return null
            if (l <= 0.0) return null
            return when (cause) {
                NotTakenCause.HOURLY_MIN, NotTakenCause.BLOCK_PAY_MIN, NotTakenCause.LEAD_TIME ->
                    (l - f) / l
                NotTakenCause.BLOCK_PAY_MAX, NotTakenCause.DURATION ->
                    abs(f - l) / l
                else -> null
            }
        }

    fun isNearMiss(): Boolean {
        val f = found ?: return false
        val l = limit ?: return false
        return when (cause) {
            NotTakenCause.BLOCK_PAY_MIN -> l - f <= maxOf(1.0, l * 0.10)
            NotTakenCause.HOURLY_MIN -> l - f <= l * 0.10
            NotTakenCause.BLOCK_PAY_MAX -> true
            NotTakenCause.LEAD_TIME -> l - f <= maxOf(15.0, l * 0.15)
            NotTakenCause.DURATION -> abs(f - l) <= maxOf(0.25, l * 0.15)
            else -> false
        }
    }

    fun ruleLabel(): String = when {
        ruleNumber == null -> ""
        ruleTitle.isNotBlank() -> "Regla #$ruleNumber $ruleTitle"
        else -> "Regla #$ruleNumber"
    }

    fun conditionLabel(): String {
        val f = found
        val l = limit
        if (f == null || l == null) return cause.label.lowercase()
        return when (cause) {
            NotTakenCause.BLOCK_PAY_MIN ->
                "pago ${money(f)} < mín ${money(l)} (faltó ${money(l - f)})"
            NotTakenCause.BLOCK_PAY_MAX ->
                "pago ${money(f)} > máx ${money(l)} (sobró ${money(f - l)})"
            NotTakenCause.HOURLY_MIN ->
                "${hourly(f)} < mín ${hourly(l)} (faltó ${hourly(l - f)})"
            NotTakenCause.LEAD_TIME ->
                "lead ${f.toInt()} min < mín ${l.toInt()} min (faltaron ${(l - f).toInt()} min)"
            NotTakenCause.DURATION ->
                "duración ${"%.1f".format(f)} h vs límite ${"%.1f".format(l)} h"
            else -> cause.label.lowercase()
        }
    }

    companion object {
        fun money(v: Double) = "\$%.2f".format(v)
        fun hourly(v: Double) = "\$%.2f/h".format(v)
    }
}

object NotTakenReasonParser {

    private val RULE_QUOTED = Regex("""Regla\s*«([^»]+)»""")
    private val RULE_HASH = Regex("""Regla\s*#(\d+)\s*([^\]:]*)""", RegexOption.IGNORE_CASE)
    private const val NUM = "(\\d+(?:\\.\\d+)?)"
    private const val USD = "\\$"
    private val PAY_MIN = Regex(
        "(?:Pago|Detalle pago)\\s*$USD$NUM\\s*<\\s*m[ií]nimo\\s*$USD$NUM",
        RegexOption.IGNORE_CASE,
    )
    private val HOURLY_MIN = Regex(
        "(?:\\$/h|Detalle\\s*\\$/h)\\s*$NUM\\s*<\\s*m[ií]nimo\\s*$NUM",
        RegexOption.IGNORE_CASE,
    )
    private val PAY_GENERIC_MIN = Regex(
        "precio\\s*$USD$NUM\\s*<\\s*m[ií]n\\s*$USD$NUM",
        RegexOption.IGNORE_CASE,
    )

    fun parse(reason: String, offer: OfferLogEntry? = null, rules: List<FlexTariffRule> = emptyList()): NotTakenDiagnosis {
        val text = reason.replace(Regex("\\s+"), " ").trim()
        val (ruleNumber, ruleTitle) = extractRule(text)

        fun diag(cause: NotTakenCause, found: Double? = null, limit: Double? = null) =
            NotTakenDiagnosis(cause, ruleNumber, ruleTitle, found, limit)

        fun pair(m: MatchResult) = m.groupValues[1].toDouble() to m.groupValues[2].toDouble()

        if (text.contains("Ninguna regla", ignoreCase = true) ||
            text.equals("Sin reglas de tarifa activas", ignoreCase = true)
        ) {
            if (offer != null) {
                return reDiagnoseAgainstRules(offer, rules)
            }
            return diag(NotTakenCause.NO_RULE_MATCH)
        }

        PAY_MIN.find(text)?.let { val (f, l) = pair(it); return diag(NotTakenCause.BLOCK_PAY_MIN, f, l) }
        PAY_GENERIC_MIN.find(text)?.let { val (f, l) = pair(it); return diag(NotTakenCause.BLOCK_PAY_MIN, f, l) }
        HOURLY_MIN.find(text)?.let { val (f, l) = pair(it); return diag(NotTakenCause.HOURLY_MIN, f, l) }

        return when {
            text.contains("palabra excluida", true) || text.contains("keyword excluido", true) ->
                diag(NotTakenCause.KEYWORD_EXCLUDED)
            text.contains("Detalle no coincide", true) || text.contains("Lista ≠", true) ||
                text.contains("Lista !=", true) ->
                diag(NotTakenCause.DETAIL_MISMATCH)
            text.contains("Datos incompletos", true) || text.contains("sin pago", true) ||
                text.contains("sin \$/h", true) ->
                diag(NotTakenCause.INCOMPLETE_DATA)
            text.contains("Sin confirmación", true) ->
                diag(NotTakenCause.TAKE_MISS_NO_CONFIRM)
            text.contains("reserved", true) || text.contains("no disponible", true) ||
                text.contains("unavailable", true) || text.contains("Someone else", true) ->
                diag(NotTakenCause.TAKE_MISS_UNAVAILABLE)
            text.contains("horario del teléfono", true) ||
                (text.contains("horario", true) && text.contains("fuera", true)) ->
                diag(NotTakenCause.PHONE_SCHEDULE)
            text.contains("día", true) && (text.contains("semana", true) || text.contains("habilitado", true)) ->
                diag(NotTakenCause.WEEKDAY)
            text.contains("tipo de bloque", true) || text.contains("block type", true) ->
                diag(NotTakenCause.BLOCK_TYPE)
            text.contains("lead", true) || text.contains("anticip", true) ->
                diag(NotTakenCause.LEAD_TIME)
            text.contains("duraci", true) ->
                diag(NotTakenCause.DURATION)
            text.contains("inicio", true) && (text.contains("ventana", true) || text.contains("mínimo", true)) ->
                diag(NotTakenCause.BLOCK_START_WINDOW)
            text.contains("estación sin", true) || text.contains("tienda no incluida", true) ->
                diag(NotTakenCause.STATION_UNCOVERED)
            text.contains("✓") || text.contains("Cumple", true) || text.contains("Clásico:", true) ->
                diag(NotTakenCause.PASSED_NOT_TAKEN)
            text.contains("No cumple", true) ->
                diag(NotTakenCause.NO_RULE_MATCH)
            else -> diag(NotTakenCause.OTHER)
        }
    }

    private fun extractRule(text: String): Pair<Int?, String> {
        RULE_QUOTED.find(text)?.let { return null to it.groupValues[1].trim() }
        RULE_HASH.find(text)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].trim()
        }
        return null to ""
    }

    /**
     * Cuando el motivo es «Ninguna regla coincide», re-evalúa contra las reglas actuales
     * y elige el near-miss más cercano o el fallo dominante.
     */
    fun reDiagnoseAgainstRules(offer: OfferLogEntry, rules: List<FlexTariffRule>): NotTakenDiagnosis {
        val active = rules.filter { it.enabled }.sortedBy { it.sortOrder }
        if (active.isEmpty()) {
            return NotTakenDiagnosis(NotTakenCause.NO_RULE_MATCH)
        }
        val stationMatches = active.filter { FlexStationMatcher.matches(offer.station, it) }
        if (stationMatches.isEmpty()) {
            return NotTakenDiagnosis(NotTakenCause.STATION_UNCOVERED)
        }

        val failures = stationMatches.mapNotNull { rule -> firstFailingCriterion(offer, rule) }
        if (failures.isEmpty()) {
            return NotTakenDiagnosis(NotTakenCause.PASSED_NOT_TAKEN)
        }

        val near = failures.filter { it.isNearMiss() && it.cause.tunable }
            .minByOrNull { it.relativeGap ?: 1.0 }
        if (near != null) return near

        // Dominante: el fallo más frecuente entre reglas; desempate por nearness / orden.
        val byCause = failures.groupBy { it.cause }
        val dominantCause = byCause.maxWithOrNull(
            compareBy<Map.Entry<NotTakenCause, List<NotTakenDiagnosis>>> { it.value.size }
                .thenByDescending { e -> e.value.count { it.cause.tunable } },
        )?.key ?: failures.first().cause
        return failures.firstOrNull { it.cause == dominantCause } ?: failures.first()
    }

    /** Primera condición que falla en [rule] para [offer] (estación ya filtrada). */
    fun firstFailingCriterion(offer: OfferLogEntry, rule: FlexTariffRule): NotTakenDiagnosis? {
        val n = rule.sortOrder + 1
        val title = RuleSuggestionMatcher.normalizeTitle(rule.displayTitle())
        fun diag(cause: NotTakenCause, found: Double? = null, limit: Double? = null) =
            NotTakenDiagnosis(cause, n, title, found, limit)

        val lower = "${offer.station} ${offer.timeWindow} ${offer.reason}".lowercase()
        val detectedType = detectBlockType(lower)
        if (rule.blockType != FlexBlockTypeFilter.ALL && rule.blockType != detectedType) {
            return diag(NotTakenCause.BLOCK_TYPE)
        }

        when (rule.payMode) {
            FlexPayCriteriaMode.BLOCK_PAY -> {
                if (offer.price < rule.priceMin) {
                    return diag(NotTakenCause.BLOCK_PAY_MIN, offer.price, rule.priceMin)
                }
                rule.priceMax?.let { max ->
                    if (offer.price > max) return diag(NotTakenCause.BLOCK_PAY_MAX, offer.price, max)
                }
            }
            FlexPayCriteriaMode.HOURLY_PAY -> {
                if (offer.hourlyRate < rule.minHourlyRate) {
                    return diag(NotTakenCause.HOURLY_MIN, offer.hourlyRate, rule.minHourlyRate)
                }
            }
            FlexPayCriteriaMode.HOURLY_AND_BLOCK -> {
                if (offer.hourlyRate < rule.minHourlyRate) {
                    return diag(NotTakenCause.HOURLY_MIN, offer.hourlyRate, rule.minHourlyRate)
                }
                if (offer.price < rule.priceMin) {
                    return diag(NotTakenCause.BLOCK_PAY_MIN, offer.price, rule.priceMin)
                }
                rule.priceMax?.let { max ->
                    if (offer.price > max) return diag(NotTakenCause.BLOCK_PAY_MAX, offer.price, max)
                }
            }
            FlexPayCriteriaMode.MANUAL_FIXED -> {
                if (abs(offer.price - rule.priceMin) >= 0.51) {
                    return diag(NotTakenCause.BLOCK_PAY_MIN, offer.price, rule.priceMin)
                }
            }
            FlexPayCriteriaMode.MANUAL_ANY -> Unit
        }

        if (rule.blockStartFilterEnabled) {
            val startMin = FlexGrabberEvaluator.parseStartMinutesOfDay(offer.timeWindow)
            if (startMin == null) return diag(NotTakenCause.BLOCK_START_WINDOW)
            val from = rule.blockStartFromMinutes
            val to = rule.blockStartToMinutes
            val ok = if (from <= to) startMin in from..to else startMin >= from || startMin <= to
            if (!ok) return diag(NotTakenCause.BLOCK_START_WINDOW, startMin.toDouble(), from.toDouble())
        }

        rule.minLeadTimeMinutes?.let { required ->
            val until = FlexGrabberEvaluator.minutesUntilBlockStart(offer.timeWindow)
            if (until == null || until < required) {
                return diag(NotTakenCause.LEAD_TIME, until?.toDouble() ?: 0.0, required.toDouble())
            }
        }

        val hours = offer.durationHours.takeIf { it > 0 }
            ?: FlexGrabberEvaluator.parseDurationHours(offer.timeWindow)
        if (hours != null) {
            rule.minDurationHours?.let { min ->
                if (hours < min) return diag(NotTakenCause.DURATION, hours, min)
            }
            rule.maxDurationHours?.let { max ->
                if (hours > max) return diag(NotTakenCause.DURATION, hours, max)
            }
        }

        if (rule.weekdaysEnabled && rule.allowedWeekdays.isNotEmpty()) {
            val dow = Calendar.getInstance().apply { timeInMillis = offer.timestamp }
                .get(Calendar.DAY_OF_WEEK)
            if (dow !in rule.allowedWeekdays) return diag(NotTakenCause.WEEKDAY)
        }

        if (rule.timeEnabled) {
            val cal = Calendar.getInstance().apply { timeInMillis = offer.timestamp }
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val start = rule.timeStartMinutes
            val end = rule.timeEndMinutes
            val ok = if (start <= end) nowMin in start..end else nowMin >= start || nowMin <= end
            if (!ok) return diag(NotTakenCause.PHONE_SCHEDULE)
        }

        for (kw in rule.excludedKeywords) {
            val k = kw.trim()
            if (k.isNotEmpty() && lower.contains(k, ignoreCase = true)) {
                return diag(NotTakenCause.KEYWORD_EXCLUDED)
            }
        }

        return null
    }

    private fun detectBlockType(lowerText: String): FlexBlockTypeFilter = when {
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
}

data class AnalyzedNotTaken(
    val offer: OfferLogEntry,
    val diagnosis: NotTakenDiagnosis,
    val good: Boolean,
)

data class CauseStat(
    val cause: NotTakenCause,
    val count: Int,
    val avgPrice: Double,
    val minPrice: Double,
    val maxPrice: Double,
    val avgHourly: Double,
    val nearMisses: Int,
    val good: Int,
)

data class PriceBucket(
    val label: String,
    val notTaken: Int,
    val accepted: Int,
    val good: Int,
    val avgHourlyNotTaken: Double,
)

data class StationStat(
    val station: String,
    val count: Int,
    val minPrice: Double,
    val maxPrice: Double,
    val avgHourly: Double,
    val good: Int,
)

data class RuleSuggestion(
    val ruleNumber: Int,
    val ruleTitle: String,
    val cause: NotTakenCause,
    val currentLimit: Double,
    val suggestedLimit: Double,
    val wouldPass: Int,
    val wouldPassValue: Double,
    val rejectedByThis: Int,
    val wouldPassGood: Int = 0,
) {
    fun text(): String {
        val rule = if (ruleTitle.isNotBlank()) "Regla #$ruleNumber $ruleTitle" else "Regla #$ruleNumber"
        return "$rule: ${effectText()}"
    }

    fun effectText(): String {
        val goodNote = if (wouldPassGood > 0) ", $wouldPassGood buenas" else ""
        return "${changeText()} dejaría pasar $wouldPass de $rejectedByThis no tomadas por esta condición " +
            "(${NotTakenDiagnosis.money(wouldPassValue)} en total$goodNote)."
    }

    fun changeText(): String =
        when (cause) {
            NotTakenCause.BLOCK_PAY_MIN ->
                "bajar pago mín de ${NotTakenDiagnosis.money(currentLimit)} a ${NotTakenDiagnosis.money(suggestedLimit)}"
            NotTakenCause.BLOCK_PAY_MAX ->
                "subir pago máx de ${NotTakenDiagnosis.money(currentLimit)} a ${NotTakenDiagnosis.money(suggestedLimit)}"
            NotTakenCause.HOURLY_MIN ->
                "bajar \$/h mín de ${NotTakenDiagnosis.hourly(currentLimit)} a ${NotTakenDiagnosis.hourly(suggestedLimit)}"
            NotTakenCause.LEAD_TIME ->
                "bajar lead mín de ${currentLimit.toInt()} a ${suggestedLimit.toInt()} min"
            NotTakenCause.DURATION ->
                "ajustar duración límite de ${"%.1f".format(currentLimit)} h a ${"%.1f".format(suggestedLimit)} h"
            else -> cause.label
        }
}

/** Enlaza sugerencias del análisis con las reglas Flex actuales. */
object RuleSuggestionMatcher {

    fun currentLimit(rule: FlexTariffRule, cause: NotTakenCause): Double? = when (cause) {
        NotTakenCause.BLOCK_PAY_MIN -> rule.priceMin
        NotTakenCause.BLOCK_PAY_MAX -> rule.priceMax
        NotTakenCause.HOURLY_MIN -> rule.minHourlyRate
        NotTakenCause.LEAD_TIME -> rule.minLeadTimeMinutes?.toDouble()
        NotTakenCause.DURATION -> rule.minDurationHours ?: rule.maxDurationHours
        else -> null
    }

    fun forRule(rule: FlexTariffRule, suggestions: List<RuleSuggestion>): List<RuleSuggestion> {
        val title = normalizeTitle(rule.displayTitle())
        return suggestions.filter { s ->
            s.wouldPass > 0 &&
                s.ruleNumber == rule.sortOrder + 1 &&
                normalizeTitle(s.ruleTitle) == title &&
                currentLimit(rule, s.cause)?.let { abs(it - s.currentLimit) < 0.005 } == true
        }
    }

    fun apply(rule: FlexTariffRule, s: RuleSuggestion): FlexTariffRule = when (s.cause) {
        NotTakenCause.BLOCK_PAY_MIN -> rule.copy(priceMin = s.suggestedLimit)
        NotTakenCause.BLOCK_PAY_MAX -> rule.copy(priceMax = s.suggestedLimit)
        NotTakenCause.HOURLY_MIN -> rule.copy(minHourlyRate = s.suggestedLimit)
        NotTakenCause.LEAD_TIME -> rule.copy(minLeadTimeMinutes = s.suggestedLimit.toInt().coerceAtLeast(0))
        NotTakenCause.DURATION -> {
            val min = rule.minDurationHours
            val max = rule.maxDurationHours
            when {
                min != null && abs(min - s.currentLimit) < 0.01 ->
                    rule.copy(minDurationHours = s.suggestedLimit)
                max != null && abs(max - s.currentLimit) < 0.01 ->
                    rule.copy(maxDurationHours = s.suggestedLimit)
                else -> rule
            }
        }
        else -> rule
    }

    fun status(
        s: RuleSuggestion,
        rules: List<FlexTariffRule>,
        applied: List<AppliedSuggestion> = emptyList(),
    ): SuggestionStatus {
        val rule = findRule(s, rules) ?: return SuggestionStatus(SuggestionState.RULE_MISSING)
        val current = currentLimit(rule, s.cause)
        val record = applied.filter { it.matches(s) }.maxByOrNull { it.appliedAt }
        val state = when {
            current == null -> SuggestionState.APPLIED
            abs(current - s.currentLimit) < EPS -> SuggestionState.PENDING
            reached(s.cause, current, s.suggestedLimit) -> SuggestionState.APPLIED
            looser(s.cause, current, s.currentLimit) -> SuggestionState.PARTIAL
            else -> SuggestionState.TIGHTENED
        }
        return SuggestionStatus(state, current, record?.appliedAt?.takeIf { state != SuggestionState.PENDING })
    }

    private fun findRule(s: RuleSuggestion, rules: List<FlexTariffRule>): FlexTariffRule? {
        val title = normalizeTitle(s.ruleTitle)
        val sameTitle = rules.filter { normalizeTitle(it.displayTitle()) == title }
        return sameTitle.firstOrNull { it.sortOrder + 1 == s.ruleNumber } ?: sameTitle.singleOrNull()
    }

    private fun lowerIsLooser(cause: NotTakenCause) =
        cause == NotTakenCause.BLOCK_PAY_MIN ||
            cause == NotTakenCause.HOURLY_MIN ||
            cause == NotTakenCause.LEAD_TIME

    private fun reached(cause: NotTakenCause, current: Double, suggested: Double) =
        if (lowerIsLooser(cause)) current <= suggested + EPS else current >= suggested - EPS

    private fun looser(cause: NotTakenCause, current: Double, original: Double) =
        if (lowerIsLooser(cause)) current < original else current > original

    fun formatLimit(cause: NotTakenCause, v: Double?): String = when {
        v == null -> "sin límite"
        cause == NotTakenCause.BLOCK_PAY_MIN || cause == NotTakenCause.BLOCK_PAY_MAX ->
            NotTakenDiagnosis.money(v)
        cause == NotTakenCause.HOURLY_MIN -> NotTakenDiagnosis.hourly(v)
        cause == NotTakenCause.LEAD_TIME -> "${v.toInt()} min"
        cause == NotTakenCause.DURATION -> "%.1f h".format(v)
        else -> "%.2f".format(v)
    }

    fun normalizeTitle(t: String): String =
        t.replace(",", ";").replace(Regex("\\s+"), " ").trim()

    private const val EPS = 0.005
}

enum class SuggestionState(val label: String) {
    PENDING("Pendiente"),
    APPLIED("Aplicada"),
    PARTIAL("Ajuste parcial"),
    TIGHTENED("Regla más estricta"),
    RULE_MISSING("Regla no encontrada"),
}

data class SuggestionStatus(
    val state: SuggestionState,
    val currentLimit: Double? = null,
    val appliedAt: Long? = null,
)

data class AppliedSuggestion(
    val ruleTitle: String,
    val cause: NotTakenCause,
    val fromLimit: Double,
    val toLimit: Double,
    val appliedAt: Long,
) {
    fun matches(s: RuleSuggestion): Boolean =
        cause == s.cause &&
            abs(fromLimit - s.currentLimit) < 0.005 &&
            RuleSuggestionMatcher.normalizeTitle(ruleTitle) ==
            RuleSuggestionMatcher.normalizeTitle(s.ruleTitle)
}

data class NotTakenReport(
    val uniqueNotTaken: Int,
    val rawNotTakenRows: Int,
    val accepted: Int,
    val notTakenValue: Double,
    val goodPriceMin: Double,
    val goodHourlyMin: Double,
    val goodFromAccepted: Boolean,
    val good: List<AnalyzedNotTaken>,
    val nearMisses: List<AnalyzedNotTaken>,
    val byCause: List<CauseStat>,
    val priceBuckets: List<PriceBucket>,
    val uncoveredStations: List<StationStat>,
    val suggestions: List<RuleSuggestion>,
    val missBreakdown: List<AnalyzedNotTaken>,
    val generatedAt: Long = 0L,
    val latestEntryAt: Long = 0L,
)

/**
 * Análisis de ofertas no tomadas (rechazadas / perdidas / canceladas / SEEN filtro)
 * para detectar reglas demasiado exigentes. Solo lee el historial.
 */
object NotTakenAnalyzer {

    const val DEFAULT_GOOD_PRICE = 60.0
    const val DEFAULT_GOOD_HOURLY = 22.0
    private const val MIN_ACCEPTED_FOR_BASELINE = 5
    const val DEDUP_WINDOW_MS = 20 * 60 * 1000L

    private val BUCKETS = listOf(
        0.0 to 40.0, 40.0 to 60.0, 60.0 to 80.0, 80.0 to 100.0,
        100.0 to 120.0, 120.0 to 150.0, 150.0 to Double.MAX_VALUE,
    )

    private val FILTER_SEEN_MARKERS = listOf(
        "Ninguna regla",
        "No cumple",
        "Clásico:",
        "palabra excluida",
        "Sin reglas",
        "Datos incompletos",
        "< mínimo",
        "Regla «",
    )

    fun isFilterLikeSeen(reason: String): Boolean {
        if (reason.equals("Vista en pantalla", ignoreCase = true)) return false
        return FILTER_SEEN_MARKERS.any { reason.contains(it, ignoreCase = true) }
    }

    fun isNotTakenCandidate(entry: OfferLogEntry): Boolean = when (entry.status) {
        OfferStatus.REJECTED, OfferStatus.MISS, OfferStatus.CANCELLED -> true
        OfferStatus.SEEN -> isFilterLikeSeen(entry.reason)
        else -> false
    }

    fun analyze(
        entries: List<OfferLogEntry>,
        now: Long = System.currentTimeMillis(),
        rules: List<FlexTariffRule> = emptyList(),
    ): NotTakenReport {
        val sorted = entries.sortedBy { it.timestamp }
        val notTakenRows = sorted.filter { isNotTakenCandidate(it) }
        val acceptedList = sorted.filter {
            it.status == OfferStatus.ACCEPTED || it.status == OfferStatus.SIMULATED
        }
        val unique = dedupe(notTakenRows)

        val baseline = acceptedList.size >= MIN_ACCEPTED_FOR_BASELINE
        val goodPrice = if (baseline) percentile(acceptedList.map { it.price }, 0.25) else DEFAULT_GOOD_PRICE
        val goodHourly = if (baseline) {
            percentile(acceptedList.map { it.hourlyRate }.filter { it > 0 }, 0.25)
                .takeIf { it > 0 } ?: DEFAULT_GOOD_HOURLY
        } else {
            DEFAULT_GOOD_HOURLY
        }

        val analyzed = unique.map { offer ->
            val diagnosis = when (offer.status) {
                OfferStatus.MISS -> diagnoseMiss(offer, rules)
                else -> NotTakenReasonParser.parse(offer.reason, offer, rules)
            }
            AnalyzedNotTaken(
                offer = offer,
                diagnosis = diagnosis,
                good = offer.price >= goodPrice && offer.hourlyRate >= goodHourly,
            )
        }

        val byCause = analyzed.groupBy { it.diagnosis.cause }.map { (cause, list) ->
            CauseStat(
                cause = cause,
                count = list.size,
                avgPrice = list.map { it.offer.price }.average(),
                minPrice = list.minOf { it.offer.price },
                maxPrice = list.maxOf { it.offer.price },
                avgHourly = list.map { it.offer.hourlyRate }.average(),
                nearMisses = list.count { it.diagnosis.isNearMiss() },
                good = list.count { it.good },
            )
        }.sortedByDescending { it.count }

        val buckets = BUCKETS.map { (lo, hi) ->
            val rej = analyzed.filter { it.offer.price >= lo && it.offer.price < hi }
            val acc = acceptedList.count { it.price >= lo && it.price < hi }
            PriceBucket(
                label = if (hi == Double.MAX_VALUE) "\$${lo.toInt()}+" else "\$${lo.toInt()}–${hi.toInt()}",
                notTaken = rej.size,
                accepted = acc,
                good = rej.count { it.good },
                avgHourlyNotTaken = rej.map { it.offer.hourlyRate }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            )
        }

        val stations = analyzed.filter {
            it.diagnosis.cause == NotTakenCause.STATION_UNCOVERED ||
                it.diagnosis.cause == NotTakenCause.NO_RULE_MATCH
        }
            .groupBy { it.offer.station.trim().ifBlank { "(sin estación)" } }
            .map { (station, list) ->
                StationStat(
                    station = station,
                    count = list.size,
                    minPrice = list.minOf { it.offer.price },
                    maxPrice = list.maxOf { it.offer.price },
                    avgHourly = list.map { it.offer.hourlyRate }.average(),
                    good = list.count { it.good },
                )
            }
            .sortedWith(compareByDescending<StationStat> { it.good }.thenByDescending { it.count })

        val near = analyzed.filter { it.diagnosis.isNearMiss() }
            .sortedWith(
                compareByDescending<AnalyzedNotTaken> { it.good }
                    .thenBy { it.diagnosis.relativeGap ?: 1.0 }
                    .thenByDescending { it.offer.price },
            )

        val missOnly = analyzed.filter {
            it.offer.status == OfferStatus.MISS ||
                it.diagnosis.cause.name.startsWith("TAKE_MISS_")
        }.sortedByDescending { it.offer.timestamp }

        return NotTakenReport(
            generatedAt = now,
            latestEntryAt = sorted.lastOrNull()?.timestamp ?: 0L,
            uniqueNotTaken = analyzed.size,
            rawNotTakenRows = notTakenRows.size,
            accepted = acceptedList.size,
            notTakenValue = analyzed.sumOf { it.offer.price },
            goodPriceMin = goodPrice,
            goodHourlyMin = goodHourly,
            goodFromAccepted = baseline,
            good = analyzed.filter { it.good }.sortedByDescending { it.offer.price },
            nearMisses = near,
            byCause = byCause,
            priceBuckets = buckets,
            uncoveredStations = stations.filter { it.count > 0 },
            suggestions = suggestions(analyzed),
            missBreakdown = missOnly,
        )
    }

    private fun diagnoseMiss(offer: OfferLogEntry, rules: List<FlexTariffRule>): NotTakenDiagnosis {
        val parsed = NotTakenReasonParser.parse(offer.reason, offer, rules)
        if (parsed.cause != NotTakenCause.OTHER && parsed.cause != NotTakenCause.NO_RULE_MATCH) {
            return parsed
        }
        val r = offer.reason
        return when {
            r.contains("Sin confirmación", true) ->
                NotTakenDiagnosis(NotTakenCause.TAKE_MISS_NO_CONFIRM)
            r.contains("reserved", true) || r.contains("unavailable", true) ||
                r.contains("no disponible", true) ->
                NotTakenDiagnosis(NotTakenCause.TAKE_MISS_UNAVAILABLE)
            else -> NotTakenDiagnosis(NotTakenCause.TAKE_MISS_OTHER)
        }
    }

    fun dedupe(rows: List<OfferLogEntry>): List<OfferLogEntry> {
        val lastKept = mutableMapOf<String, Int>()
        val out = mutableListOf<OfferLogEntry>()
        for (row in rows.sortedBy { it.timestamp }) {
            val sig = "${row.station.trim().lowercase()}|" +
                "${"%.2f".format(row.price)}|${row.timeWindow.trim().lowercase()}"
            val idx = lastKept[sig]
            if (idx != null && row.timestamp - out[idx].timestamp <= DEDUP_WINDOW_MS) {
                out[idx] = preferRicher(out[idx], row)
            } else {
                lastKept[sig] = out.size
                out.add(row)
            }
        }
        return out
    }

    /** Prefiere REJECTED/MISS sobre SEEN y el motivo más informativo. */
    private fun preferRicher(a: OfferLogEntry, b: OfferLogEntry): OfferLogEntry {
        fun rank(s: OfferStatus) = when (s) {
            OfferStatus.REJECTED -> 3
            OfferStatus.MISS, OfferStatus.CANCELLED -> 2
            OfferStatus.SEEN -> 1
            else -> 0
        }
        return when {
            rank(b.status) > rank(a.status) -> b
            rank(a.status) > rank(b.status) -> a
            b.reason.length > a.reason.length -> b
            else -> b
        }
    }

    private fun suggestions(analyzed: List<AnalyzedNotTaken>): List<RuleSuggestion> {
        val out = mutableListOf<RuleSuggestion>()
        analyzed.filter { it.diagnosis.cause.tunable && it.diagnosis.ruleNumber != null }
            .filter { it.diagnosis.limit != null }
            .groupBy {
                SuggestionKey(
                    it.diagnosis.ruleNumber!!,
                    it.diagnosis.ruleTitle,
                    it.diagnosis.cause,
                    it.diagnosis.limit!!,
                )
            }
            .forEach { (key, list) ->
                val near = list.filter { it.diagnosis.isNearMiss() || it.good }
                if (near.isEmpty()) return@forEach
                val limit = key.limit
                val founds = near.mapNotNull { it.diagnosis.found }
                if (founds.isEmpty()) return@forEach
                val suggested = when (key.cause) {
                    NotTakenCause.BLOCK_PAY_MIN -> floor(founds.min())
                    NotTakenCause.HOURLY_MIN -> floor(founds.min() * 100) / 100
                    NotTakenCause.BLOCK_PAY_MAX -> ceil(founds.max())
                    NotTakenCause.LEAD_TIME -> floor(founds.min())
                    NotTakenCause.DURATION -> {
                        // Si falló por mínimo, bajar; si por máximo, subir.
                        val above = founds.count { it > limit }
                        if (above > founds.size / 2) ceil(founds.max() * 10) / 10
                        else floor(founds.min() * 10) / 10
                    }
                    else -> return@forEach
                }
                val passes = list.filter { a ->
                    val f = a.diagnosis.found ?: return@filter false
                    when (key.cause) {
                        NotTakenCause.BLOCK_PAY_MIN, NotTakenCause.HOURLY_MIN, NotTakenCause.LEAD_TIME ->
                            f >= suggested
                        NotTakenCause.BLOCK_PAY_MAX -> f <= suggested
                        NotTakenCause.DURATION -> {
                            if (suggested < limit) f >= suggested else f <= suggested
                        }
                        else -> false
                    }
                }
                out += RuleSuggestion(
                    ruleNumber = key.number,
                    ruleTitle = key.title,
                    cause = key.cause,
                    currentLimit = limit,
                    suggestedLimit = suggested,
                    wouldPass = passes.size,
                    wouldPassValue = passes.sumOf { it.offer.price },
                    rejectedByThis = list.size,
                    wouldPassGood = passes.count { it.good },
                )
            }
        return out.sortedWith(
            compareByDescending<RuleSuggestion> { it.wouldPassGood }
                .thenByDescending { it.wouldPassValue },
        )
    }

    private data class SuggestionKey(
        val number: Int,
        val title: String,
        val cause: NotTakenCause,
        val limit: Double,
    )

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val idx = ((s.size - 1) * p).toInt().coerceIn(0, s.lastIndex)
        return s[idx]
    }
}
