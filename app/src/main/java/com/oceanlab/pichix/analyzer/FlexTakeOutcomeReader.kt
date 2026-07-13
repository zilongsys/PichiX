package com.oceanlab.pichix.analyzer

import com.oceanlab.pichix.data.FlexMessageHub

/**
 * Resultado tras pulsar Schedule: lee lo que muestra Flex (toast, banner o notificación).
 */
object FlexTakeOutcomeReader {

    enum class Result {
        SCHEDULED,
        BLOCK_UNAVAILABLE,
        PENDING,
    }

    data class Reading(
        val result: Result,
        val flexMessage: String,
    )

    private val scheduledPhrases = listOf(
        "offer scheduled",
        "offer schedulled",
        "block scheduled",
        "has been scheduled",
        "successfully scheduled",
        "scheduled offer",
        "you scheduled",
        "oferta programada",
        "bloque programado",
        "se ha programado",
        "programado correctamente",
        "programada correctamente",
    )

    /**
     * Evalúa cada fuente por separado (overlay → notificación reciente → pantalla)
     * para evitar falsos «unavailable» mezclando textos de la lista con el toast.
     */
    fun read(
        screenText: String,
        overlayText: String = "",
        notificationText: String = "",
    ): Reading {
        val chunks = listOf(
            overlayText.trim(),
            notificationText.trim(),
            screenText.trim(),
        ).filter { it.isNotEmpty() }
        for (chunk in chunks) {
            readingForChunk(chunk)?.let { return it }
        }
        return Reading(Result.PENDING, "")
    }

    /** Usa hub si no se pasa texto de notificación explícito. */
    fun readWithRecentNotification(
        screenText: String,
        overlayText: String = "",
        withinMs: Long = 20_000L,
    ): Reading = read(
        screenText = screenText,
        overlayText = overlayText,
        notificationText = FlexMessageHub.recentNotificationText(withinMs),
    )

    fun read(text: String): Reading = readingForChunk(text.trim()) ?: Reading(Result.PENDING, "")

    private fun readingForChunk(text: String): Reading? {
        if (text.isBlank()) return null
        if (isOfferScheduled(text)) {
            return Reading(
                Result.SCHEDULED,
                extractFlexMessage(text, scheduledPhrases),
            )
        }
        if (OfferListDetailMatcher.isBlockUnavailable(text)) {
            return Reading(
                Result.BLOCK_UNAVAILABLE,
                extractFlexMessage(text, OfferListDetailMatcher.unavailablePhrasesForLog()),
            )
        }
        return null
    }

    private fun isOfferScheduled(text: String): Boolean {
        val lower = text.lowercase()
        if (scheduledPhrases.any { lower.contains(it) }) return true
        return lower.contains("scheduled") &&
            (lower.contains("offer") || lower.contains("block") || lower.contains("oferta") ||
                lower.contains("bloque"))
    }

    private fun extractFlexMessage(text: String, phrases: List<String>): String {
        val lower = text.lowercase()
        val matched = phrases.firstOrNull { lower.contains(it) } ?: return text.take(160).trim()
        val idx = lower.indexOf(matched)
        if (idx < 0) return matched.replaceFirstChar { it.uppercase() }
        val start = text.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
        val line = text.substring(start, end).trim()
        return line.ifBlank { matched.replaceFirstChar { it.uppercase() } }.take(160)
    }
}
