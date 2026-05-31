package com.oceanlab.pichix.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FlexAlertRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val name: String = "",
    val matchText: String = "",
    val soundUri: String = "",
    val repeatCount: Int = 2,
) {
    fun displayName(): String = name.ifBlank { matchText.ifBlank { "Alerta sin nombre" } }

    fun soundLabel(): String = if (soundUri.isBlank()) "Sonido del sistema" else "Sonido personalizado"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("enabled", enabled)
        put("name", name)
        put("matchText", matchText)
        put("soundUri", soundUri)
        put("repeatCount", repeatCount.coerceIn(1, 20))
    }

    companion object {
        const val DEFAULT_REPEAT_COUNT = 2

        fun fromJson(o: JSONObject): FlexAlertRule = FlexAlertRule(
            id = o.optString("id", UUID.randomUUID().toString()),
            enabled = o.optBoolean("enabled", true),
            name = o.optString("name", ""),
            matchText = o.optString("matchText", ""),
            soundUri = o.optString("soundUri", ""),
            repeatCount = o.optInt("repeatCount", DEFAULT_REPEAT_COUNT).coerceIn(1, 20),
        )
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
