package com.oceanlab.pichix.data

import com.oceanlab.pichix.util.MatchTextParser
import com.oceanlab.pichix.util.TextMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FlexAlertRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val name: String = "",
    /** Textos que deben aparecer en la notificación (OR o AND según [matchMode]). */
    val matchTexts: List<String> = emptyList(),
    val matchMode: String = MATCH_ANY,
    val textMatchMode: String = AppSettings.TEXT_MATCH_CONTAINS,
    val ignoreCase: Boolean = true,
    val soundUri: String = "",
    val repeatCount: Int = 2,
) {
    fun displayName(): String {
        val first = effectiveMatchTexts().firstOrNull().orEmpty()
        return name.ifBlank { first.ifBlank { "Alerta sin nombre" } }
    }

    fun soundLabel(): String = if (soundUri.isBlank()) "Sonido del sistema" else "Sonido personalizado"

    fun effectiveMatchTexts(): List<String> = matchTexts.map { it.trim() }.filter { it.isNotEmpty() }

    fun matchesNotificationText(text: String): Boolean {
        val patterns = effectiveMatchTexts()
        if (patterns.isEmpty()) return false
        return when (matchMode) {
            MATCH_ALL -> patterns.all { pattern ->
                TextMatcher.matches(text, pattern, textMatchMode, ignoreCase)
            }
            else -> patterns.any { pattern ->
                TextMatcher.matches(text, pattern, textMatchMode, ignoreCase)
            }
        }
    }

    fun matchSummary(): String {
        val texts = effectiveMatchTexts()
        if (texts.isEmpty()) return "(sin textos)"
        val comb = if (matchMode == MATCH_ALL) "todas" else "cualquiera"
        val how = if (textMatchMode == AppSettings.TEXT_MATCH_EXACT) "exacto" else "contiene"
        val case = if (ignoreCase) "sin distinguir mayúsculas" else "mayúsculas exactas"
        return "${texts.joinToString(" · ")} ($comb, $how, $case)"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("enabled", enabled)
        put("name", name)
        put("matchMode", matchMode)
        put("textMatchMode", textMatchMode)
        put("ignoreCase", ignoreCase)
        val arr = JSONArray()
        effectiveMatchTexts().forEach { arr.put(it) }
        put("matchTexts", arr)
        put("soundUri", soundUri)
        put("repeatCount", repeatCount.coerceIn(1, 20))
    }

    companion object {
        const val DEFAULT_REPEAT_COUNT = 2
        const val MATCH_ANY = "any"
        const val MATCH_ALL = "all"

        fun fromJson(o: JSONObject): FlexAlertRule {
            val texts = when {
                o.has("matchTexts") -> {
                    val arr = o.getJSONArray("matchTexts")
                    (0 until arr.length()).map { arr.optString(it, "").trim() }.filter { it.isNotEmpty() }
                }
                else -> {
                    val legacy = o.optString("matchText", "").trim()
                    if (legacy.isNotEmpty()) listOf(legacy) else emptyList()
                }
            }
            return FlexAlertRule(
                id = o.optString("id", UUID.randomUUID().toString()),
                enabled = o.optBoolean("enabled", true),
                name = o.optString("name", ""),
                matchTexts = texts,
                matchMode = o.optString("matchMode", MATCH_ANY).ifBlank { MATCH_ANY },
                textMatchMode = o.optString("textMatchMode", AppSettings.TEXT_MATCH_CONTAINS)
                    .ifBlank { AppSettings.TEXT_MATCH_CONTAINS },
                ignoreCase = o.optBoolean("ignoreCase", true),
                soundUri = o.optString("soundUri", ""),
                repeatCount = o.optInt("repeatCount", DEFAULT_REPEAT_COUNT).coerceIn(1, 20),
            )
        }

        fun parseMatchInput(raw: String): List<String> = MatchTextParser.parse(raw)
    }
}

object FlexAlertRulesStore {
    fun load(settings: AppSettings): List<FlexAlertRule> {
        val raw = settings.flexAlertRulesJson
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { FlexAlertRule.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(settings: AppSettings, rules: List<FlexAlertRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        settings.flexAlertRulesJson = arr.toString()
    }
}
