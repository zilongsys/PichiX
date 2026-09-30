package com.oceanlab.pichix.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Guarda cuándo se aplicó cada sugerencia (opcional; el Análisis también muestra solo texto). */
object AppliedSuggestionsStore {

    private const val PREFS = "pichix_applied_suggestions"
    private const val KEY = "applied"
    private const val MAX_RECORDS = 200

    fun load(context: Context): List<AppliedSuggestion> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val cause = NotTakenCause.entries.firstOrNull { it.name == o.optString("cause") }
                    ?: return@mapNotNull null
                AppliedSuggestion(
                    ruleTitle = o.optString("ruleTitle"),
                    cause = cause,
                    fromLimit = o.optDouble("from"),
                    toLimit = o.optDouble("to"),
                    appliedAt = o.optLong("at"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(context: Context, s: RuleSuggestion, at: Long = System.currentTimeMillis()) {
        val all = (load(context) + AppliedSuggestion(s.ruleTitle, s.cause, s.currentLimit, s.suggestedLimit, at))
            .takeLast(MAX_RECORDS)
        val arr = JSONArray()
        all.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("ruleTitle", a.ruleTitle)
                    put("cause", a.cause.name)
                    put("from", a.fromLimit)
                    put("to", a.toLimit)
                    put("at", a.appliedAt)
                },
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
