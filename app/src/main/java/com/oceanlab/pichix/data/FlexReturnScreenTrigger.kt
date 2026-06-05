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
        if (isOffersListMarkerText(screenText)) return false
        val phrases = trigger.phrases.map { it.trim() }.filter { it.isNotEmpty() }
        if (phrases.isEmpty()) return false
        return phrases.all { phrase ->
            ScreenTextMatcher.matches(screenText, phrase, trigger.matchMode, trigger.ignoreCase)
        }
    }

    /** Evita que «updates»+«schedule» de la barra inferior disparen Return estando en ofertas. */
    private fun isOffersListMarkerText(screenText: String): Boolean {
        val lower = screenText.lowercase()
        return lower.contains("filter offers by") ||
            lower.contains("filtrar ofertas") ||
            lower.contains("filtrar por")
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

    fun defaultTriggers(): List<FlexReturnScreenTrigger> = listOf(
        FlexReturnScreenTrigger(label = "Your dashboard", phrases = listOf("your dashboard")),
        FlexReturnScreenTrigger(label = "Tu panel", phrases = listOf("tu panel", "tu tablero")),
        FlexReturnScreenTrigger(label = "Your standing", phrases = listOf("your standing")),
        FlexReturnScreenTrigger(label = "Tu reputación", phrases = listOf("tu reputación", "tu standing")),
        FlexReturnScreenTrigger(label = "Read more", phrases = listOf("read more")),
        FlexReturnScreenTrigger(label = "Leer más", phrases = listOf("leer más", "read more")),
        FlexReturnScreenTrigger(label = "Learn more", phrases = listOf("learn more")),
        FlexReturnScreenTrigger(label = "Offer details", phrases = listOf("offer details")),
        FlexReturnScreenTrigger(label = "Detalle oferta", phrases = listOf("detalle de la oferta", "detalles de la oferta")),
        FlexReturnScreenTrigger(label = "No longer available", phrases = listOf("no longer available")),
        FlexReturnScreenTrigger(label = "Ya no disponible", phrases = listOf("ya no está disponible", "no longer available")),
        FlexReturnScreenTrigger(
            label = "Pestaña Schedule (contenido)",
            phrases = listOf("scheduled blocks", "bloques programados"),
        ),
    )
}
