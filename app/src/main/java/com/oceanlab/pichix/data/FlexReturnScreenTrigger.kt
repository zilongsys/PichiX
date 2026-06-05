package com.oceanlab.pichix.data

import com.oceanlab.pichix.util.ScreenTextMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Disparador de texto para detectar que Flex salió de la lista de ofertas (Return 2).
 * Varias [phrases] en un mismo trigger = todas deben coincidir (AND).
 * Cualquier trigger activo que coincida (OR entre triggers) activa Return 2.
 */
data class FlexReturnScreenTrigger(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val label: String = "",
    val phrases: List<String> = listOf(""),
    val matchMode: String = AppSettings.TEXT_MATCH_CONTAINS,
    val ignoreCase: Boolean = true,
) {
    fun displayTitle(): String =
        label.ifBlank { phrases.filter { it.isNotBlank() }.joinToString(" + ") }

    fun matchSummary(): String {
        val mode = if (matchMode == AppSettings.TEXT_MATCH_EXACT) "Exacto" else "Contiene"
        val phrasesText = phrases.filter { it.isNotBlank() }.joinToString(" ∧ ")
        return "$mode · $phrasesText"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("enabled", enabled)
        put("label", label)
        put("matchMode", matchMode)
        put("ignoreCase", ignoreCase)
        put("phrases", JSONArray().apply { phrases.forEach { put(it) } })
    }

    companion object {
        fun fromJson(o: JSONObject): FlexReturnScreenTrigger {
            val phrasesArr = o.optJSONArray("phrases")
            val phrases = buildList {
                if (phrasesArr != null) {
                    for (i in 0 until phrasesArr.length()) {
                        add(phrasesArr.optString(i, ""))
                    }
                }
            }.ifEmpty { listOf("") }
            return FlexReturnScreenTrigger(
                id = o.optString("id", UUID.randomUUID().toString()),
                enabled = o.optBoolean("enabled", true),
                label = o.optString("label", ""),
                phrases = phrases,
                matchMode = when (o.optString("matchMode", AppSettings.TEXT_MATCH_CONTAINS)) {
                    AppSettings.TEXT_MATCH_EXACT -> AppSettings.TEXT_MATCH_EXACT
                    else -> AppSettings.TEXT_MATCH_CONTAINS
                },
                ignoreCase = o.optBoolean("ignoreCase", true),
            )
        }
    }
}

object FlexReturnTriggersEvaluator {
    fun matches(screenText: String, trigger: FlexReturnScreenTrigger): Boolean {
        if (!trigger.enabled) return false
        val phrases = trigger.phrases.map { it.trim() }.filter { it.isNotEmpty() }
        if (phrases.isEmpty()) return false
        return phrases.all { phrase ->
            ScreenTextMatcher.matches(screenText, phrase, trigger.matchMode, trigger.ignoreCase)
        }
    }

    fun anyMatches(screenText: String, triggers: List<FlexReturnScreenTrigger>): Boolean =
        triggers.any { matches(screenText, it) }

    fun firstMatch(screenText: String, triggers: List<FlexReturnScreenTrigger>): FlexReturnScreenTrigger? =
        triggers.firstOrNull { matches(screenText, it) }
}

object FlexReturnTriggersStore {
    fun load(settings: AppSettings): List<FlexReturnScreenTrigger> {
        val raw = settings.flexReturnTriggersJson
        if (raw.isBlank()) return defaultTriggers()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { FlexReturnScreenTrigger.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            defaultTriggers()
        }
    }

    fun save(settings: AppSettings, triggers: List<FlexReturnScreenTrigger>) {
        val arr = JSONArray()
        triggers.forEach { arr.put(it.toJson()) }
        settings.flexReturnTriggersJson = arr.toString()
    }

    /** Solo frases típicas fuera de la lista de ofertas (evita «read more» etc. que también salen en ofertas). */
    fun defaultTriggers(): List<FlexReturnScreenTrigger> = listOf(
        FlexReturnScreenTrigger(label = "Offer details", phrases = listOf("offer details")),
        FlexReturnScreenTrigger(label = "Detalle oferta", phrases = listOf("detalle de la oferta", "detalles de la oferta")),
        FlexReturnScreenTrigger(label = "No longer available", phrases = listOf("no longer available")),
        FlexReturnScreenTrigger(label = "Ya no disponible", phrases = listOf("ya no está disponible", "no longer available")),
        FlexReturnScreenTrigger(
            label = "Schedule / bloques",
            phrases = listOf("scheduled blocks", "bloques programados", "my schedule"),
        ),
        FlexReturnScreenTrigger(
            label = "Updates / noticias",
            phrases = listOf("your dashboard", "tu panel", "activity hub"),
        ),
    )
}
