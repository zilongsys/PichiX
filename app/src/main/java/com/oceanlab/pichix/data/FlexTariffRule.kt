package com.oceanlab.pichix.data

import com.oceanlab.pichix.R
import com.oceanlab.pichix.util.BlockDurationText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class FlexStationScope(val key: String, val label: String) {
    SPECIFIC("specific", "Específica"),
    GROUP("group", "Grupo"),
    GLOBAL("global", "Global");

    companion object {
        fun fromKey(key: String): FlexStationScope =
            entries.firstOrNull { it.key == key } ?: SPECIFIC
    }
}

enum class FlexBlockTypeFilter(val key: String, val label: String) {
    ALL("all", "Todos"),
    WHOLE_FOODS("whole_foods", "Whole Foods"),
    AMAZON_COM("amazon_com", "Amazon.com"),
    SUB_SAME_DAY("sub_same_day", "Sub Same Day"),
    OTHER("other", "Otros");

    companion object {
        fun fromKey(key: String): FlexBlockTypeFilter {
            return when (key) {
                "whole_foods" -> WHOLE_FOODS
                "amazon_com" -> AMAZON_COM
                "sub_same_day", "instant", "sameday" -> SUB_SAME_DAY
                "other", "standard", "long", "surge" -> OTHER
                "all" -> ALL
                else -> entries.firstOrNull { it.key == key } ?: ALL
            }
        }
    }
}

enum class FlexPayCriteriaMode(val key: String, val label: String) {
    BLOCK_PAY("block", "Por pago de bloque"),
    HOURLY_PAY("hourly", "Por pago por hora"),
    MANUAL_FIXED("manual_fixed", "Manual (solo ese precio)"),
    MANUAL_ANY("manual_any", "Manual (cualquier precio)");

    companion object {
        fun fromKey(key: String): FlexPayCriteriaMode =
            entries.firstOrNull { it.key == key } ?: BLOCK_PAY
    }
}

data class FlexTariffRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val name: String = "",
    val stationScope: FlexStationScope = FlexStationScope.SPECIFIC,
    val stationPattern: String = "",
    val blockType: FlexBlockTypeFilter = FlexBlockTypeFilter.ALL,
    val payMode: FlexPayCriteriaMode = FlexPayCriteriaMode.BLOCK_PAY,
    val priceMin: Double = 90.0,
    val priceMax: Double? = null,
    val minHourlyRate: Double = 23.0,
    val blockStartFilterEnabled: Boolean = false,
    val blockStartFromMinutes: Int = 6 * 60,
    val blockStartToMinutes: Int = 22 * 60,
    /** No aceptar si el bloque empieza en menos de estos minutos desde ahora. */
    val minLeadTimeMinutes: Int? = null,
    val timeEnabled: Boolean = false,
    val timeStartMinutes: Int = 6 * 60,
    val timeEndMinutes: Int = 22 * 60,
    /** Duración del bloque en horas; null = cualquiera. */
    val minDurationHours: Double? = null,
    val maxDurationHours: Double? = null,
    val excludedKeywords: List<String> = emptyList(),
    val weekdaysEnabled: Boolean = false,
    val allowedWeekdays: Set<Int> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("enabled", enabled)
        put("sortOrder", sortOrder)
        put("name", name)
        put("stationScope", stationScope.key)
        put("stationPattern", stationPattern)
        put("blockType", blockType.key)
        put("payMode", payMode.key)
        put("priceMin", priceMin)
        if (priceMax != null) put("priceMax", priceMax) else put("priceMax", JSONObject.NULL)
        put("minHourlyRate", minHourlyRate)
        put("blockStartFilterEnabled", blockStartFilterEnabled)
        put("blockStartFromMinutes", blockStartFromMinutes)
        put("blockStartToMinutes", blockStartToMinutes)
        if (minLeadTimeMinutes != null) put("minLeadTimeMinutes", minLeadTimeMinutes)
        else put("minLeadTimeMinutes", JSONObject.NULL)
        put("timeEnabled", timeEnabled)
        put("timeStartMinutes", timeStartMinutes)
        put("timeEndMinutes", timeEndMinutes)
        if (minDurationHours != null) put("minDurationHours", minDurationHours)
        else put("minDurationHours", JSONObject.NULL)
        if (maxDurationHours != null) put("maxDurationHours", maxDurationHours)
        else put("maxDurationHours", JSONObject.NULL)
        put("excludedKeywords", JSONArray(excludedKeywords))
        put("weekdaysEnabled", weekdaysEnabled)
        put("allowedWeekdays", JSONArray(allowedWeekdays.sorted()))
    }

    companion object {
        fun fromJson(o: JSONObject): FlexTariffRule {
            val legacyStartHour = if (o.isNull("minBlockStartHour")) null
            else o.optInt("minBlockStartHour").coerceIn(0, 23)
            val blockStartEnabled = o.optBoolean(
                "blockStartFilterEnabled",
                legacyStartHour != null,
            )
            return FlexTariffRule(
                id = o.optString("id", UUID.randomUUID().toString()),
                enabled = o.optBoolean("enabled", true),
                sortOrder = o.optInt("sortOrder", 0),
                name = o.optString("name", ""),
                stationScope = FlexStationScope.fromKey(o.optString("stationScope", "specific")),
                stationPattern = o.optString("stationPattern", ""),
                blockType = FlexBlockTypeFilter.fromKey(o.optString("blockType", "all")),
                payMode = FlexPayCriteriaMode.fromKey(
                    o.optString("payMode", "block"),
                ),
                priceMin = o.optDouble("priceMin", 90.0),
                priceMax = if (o.isNull("priceMax")) null else o.optDouble("priceMax"),
                minHourlyRate = o.optDouble("minHourlyRate", 23.0),
                blockStartFilterEnabled = blockStartEnabled,
                blockStartFromMinutes = o.optInt(
                    "blockStartFromMinutes",
                    (legacyStartHour ?: 6) * 60,
                ),
                blockStartToMinutes = o.optInt("blockStartToMinutes", 22 * 60),
                minLeadTimeMinutes = if (o.isNull("minLeadTimeMinutes")) null
                else o.optInt("minLeadTimeMinutes").coerceAtLeast(0),
                timeEnabled = o.optBoolean("timeEnabled", false),
                timeStartMinutes = o.optInt("timeStartMinutes", 360),
                timeEndMinutes = o.optInt("timeEndMinutes", 1320),
                minDurationHours = if (o.isNull("minDurationHours")) null else o.optDouble("minDurationHours"),
                maxDurationHours = if (o.isNull("maxDurationHours")) null else o.optDouble("maxDurationHours"),
                excludedKeywords = parseStringList(o.optJSONArray("excludedKeywords")),
                weekdaysEnabled = o.optBoolean("weekdaysEnabled", false),
                allowedWeekdays = parseIntSet(o.optJSONArray("allowedWeekdays")),
            )
        }

        private fun parseIntSet(arr: JSONArray?): Set<Int> {
            if (arr == null) return emptySet()
            return (0 until arr.length())
                .mapNotNull { arr.optInt(it).takeIf { v -> v in Calendar.SUNDAY..Calendar.SATURDAY } }
                .toSet()
        }

        private fun parseStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length())
                .mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
        }
    }

    fun displayTitle(): String = name.ifBlank { stationPattern.ifBlank { "Regla sin nombre" } }

    fun cardTitlePart(): String {
        val station = stationPattern.trim()
        val alias = name.trim()
        return when (stationScope) {
            FlexStationScope.GLOBAL -> "Todas las estaciones"
            FlexStationScope.GROUP -> station.ifBlank { alias.ifBlank { "Grupo" } }
            FlexStationScope.SPECIFIC -> station.ifBlank { alias.ifBlank { "Estación" } }
        }
    }

    data class ScopeUi(val badgeBackgroundRes: Int, val iconTintColorRes: Int)

    fun scopeUi(): ScopeUi = when (stationScope) {
        FlexStationScope.GLOBAL -> ScopeUi(R.drawable.badge_green, R.color.badge_green_text)
        FlexStationScope.GROUP -> ScopeUi(R.drawable.badge_blue, R.color.badge_blue_text)
        FlexStationScope.SPECIFIC -> ScopeUi(R.drawable.badge_amber, R.color.badge_amber_text)
    }

    fun cardBlockHint(): String? = when (blockType) {
        FlexBlockTypeFilter.ALL -> null
        else -> blockType.label
    }

    data class BlockTypeUi(val badgeBackgroundRes: Int, val iconTintColorRes: Int)

    fun blockTypeUi(): BlockTypeUi = when (blockType) {
        FlexBlockTypeFilter.WHOLE_FOODS -> BlockTypeUi(
            R.drawable.badge_flex_whole_foods,
            R.color.flex_block_whole_foods_icon,
        )
        FlexBlockTypeFilter.AMAZON_COM -> BlockTypeUi(
            R.drawable.badge_flex_amazon,
            R.color.flex_block_amazon_icon,
        )
        FlexBlockTypeFilter.SUB_SAME_DAY -> BlockTypeUi(
            R.drawable.badge_flex_sub_same_day,
            R.color.flex_block_sub_same_day_icon,
        )
        FlexBlockTypeFilter.OTHER -> BlockTypeUi(
            R.drawable.badge_flex_other,
            R.color.flex_block_other_icon,
        )
        FlexBlockTypeFilter.ALL -> BlockTypeUi(
            R.drawable.badge_flex_all,
            R.color.flex_block_all_icon,
        )
    }

    fun blockTypeIconRes(): Int = when (blockType) {
        FlexBlockTypeFilter.WHOLE_FOODS -> R.drawable.ic_flex_whole_foods
        FlexBlockTypeFilter.AMAZON_COM -> R.drawable.ic_flex_amazon
        FlexBlockTypeFilter.SUB_SAME_DAY -> R.drawable.ic_flex_sub_same_day
        FlexBlockTypeFilter.OTHER -> R.drawable.ic_flex_other
        FlexBlockTypeFilter.ALL -> R.drawable.ic_flex_all_blocks
    }

    fun payCriteriaLabel(): String = when (payMode) {
        FlexPayCriteriaMode.BLOCK_PAY -> priceRangeLabel()
        FlexPayCriteriaMode.HOURLY_PAY -> "$%.0f/h mín".format(minHourlyRate)
        FlexPayCriteriaMode.MANUAL_FIXED -> "Fijo $%.0f".format(priceMin)
        FlexPayCriteriaMode.MANUAL_ANY -> "Cualquier pago"
    }

    fun secondaryCriteriaLabel(): String? = when (payMode) {
        FlexPayCriteriaMode.HOURLY_PAY -> null
        FlexPayCriteriaMode.MANUAL_ANY -> null
        FlexPayCriteriaMode.MANUAL_FIXED -> null
        FlexPayCriteriaMode.BLOCK_PAY -> "$%.0f/h ref".format(minHourlyRate)
    }

    fun priceRangeLabel(): String {
        val max = priceMax?.let { "$%.0f".format(it) } ?: "∞"
        return "$%.0f - $max".format(priceMin)
    }

    fun scheduleLabel(): String {
        val parts = mutableListOf<String>()
        if (weekdaysEnabled && allowedWeekdays.isNotEmpty()) {
            parts.add(FlexTariffWeekdays.labelSet(allowedWeekdays))
        }
        if (timeEnabled) {
            parts.add("${formatMinutes(timeStartMinutes)}-${formatMinutes(timeEndMinutes)}")
        }
        if (blockStartFilterEnabled) {
            parts.add(
                "bloque ${formatMinutes(blockStartFromMinutes)}-${formatMinutes(blockStartToMinutes)}",
            )
        }
        minLeadTimeMinutes?.let { parts.add("anticip. ≥${formatLead(it)}") }
        return parts.joinToString(" · ").ifBlank { "Todo el día" }
    }

    fun durationLimitLabel(): String? {
        val min = minDurationHours?.let { "≥${BlockDurationText.formatFromHours(it)}" }
        val max = maxDurationHours?.let { "≤${BlockDurationText.formatFromHours(it)}" }
        return listOfNotNull(min, max).joinToString(" ").ifBlank { null }
    }

    fun excludedKeywordsLabel(): String = when {
        excludedKeywords.isEmpty() -> "—"
        excludedKeywords.size <= 3 -> excludedKeywords.joinToString(", ")
        else -> excludedKeywords.take(3).joinToString(", ") + " +${excludedKeywords.size - 3}"
    }

    fun previewText(): String = buildString {
        appendLine("Estación: ${displayTitle()} (${stationScope.label})")
        appendLine("Tipo: ${blockType.label}")
        appendLine("Pago: ${payMode.label} — ${payCriteriaLabel()}")
        appendLine("Inicio bloque: ${if (blockStartFilterEnabled) "${formatMinutes(blockStartFromMinutes)} a ${formatMinutes(blockStartToMinutes)}" else "sin filtro"}")
        minLeadTimeMinutes?.let { appendLine("Anticipación mín: ${formatLead(it)}") }
        appendLine("Horario regla: ${scheduleLabel()}")
        appendLine("Duración bloque: ${durationLimitLabel() ?: "cualquiera"}")
        if (excludedKeywords.isNotEmpty()) appendLine("Excluir: ${excludedKeywordsLabel()}")
    }

    private fun formatMinutes(mins: Int): String {
        val h = (mins / 60) % 24
        val m = mins % 60
        return "%02d:%02d".format(h, m)
    }

    private fun formatLead(mins: Int): String {
        val h = mins / 60
        val m = mins % 60
        return if (h > 0 && m > 0) "${h}h ${m}m" else if (h > 0) "${h}h" else "${m}m"
    }
}

object FlexTariffRulesStore {
    fun load(settings: AppSettings): List<FlexTariffRule> {
        val raw = settings.flexTariffRulesJson
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { FlexTariffRule.fromJson(arr.getJSONObject(it)) }
                .sortedBy { it.sortOrder }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(settings: AppSettings, rules: List<FlexTariffRule>) {
        val normalized = rules.mapIndexed { index, r -> r.copy(sortOrder = index) }
        val arr = JSONArray()
        normalized.forEach { arr.put(it.toJson()) }
        settings.flexTariffRulesJson = arr.toString()
    }
}
