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

    /** Textos de lista Offers que no deben marcar ACEPTADA por sí solos. */
    private val offersListMarkers = listOf(
        "filter",
        "of 0 offers",
        "of 1 offers",
        "of 2 offers",
        "of 3 offers",
        "no offers available",
        "instant offer map",
        "you're offline",
        "come back later",
    )

    /**
     * Evalúa cada fuente por separado (overlay → notificación reciente → pantalla)
     * para evitar falsos «unavailable» / «scheduled» mezclando textos de la lista.
     */
    fun read(
        screenText: String,
        overlayText: String = "",
        notificationText: String = "",
    ): Reading {
        val overlay = overlayText.trim()
        val notif = notificationText.trim()
        val screen = screenText.trim()

        // Overlay y notificación: más fiables (toast/banner).
        for (chunk in listOf(overlay, notif)) {
            if (chunk.isEmpty()) continue
            readingForChunk(chunk, allowLooseScheduled = true)?.let { return it }
        }
        // Pantalla completa: solo frases explícitas; no “scheduled” suelto en dumps de Offers.
        if (screen.isNotEmpty()) {
            readingForChunk(screen, allowLooseScheduled = false)?.let { return it }
        }
        return Reading(Result.PENDING, "")
    }

    /** Usa hub (notificación + toast in-app) si no se pasa texto explícito. */
    fun readWithRecentNotification(
        screenText: String,
        overlayText: String = "",
        withinMs: Long = 20_000L,
    ): Reading = read(
        screenText = screenText,
        overlayText = overlayText,
        notificationText = FlexMessageHub.recentFlexToastText(withinMs),
    )

    fun read(text: String): Reading =
        readingForChunk(text.trim(), allowLooseScheduled = text.length < 220)
            ?: Reading(Result.PENDING, "")

    private fun readingForChunk(text: String, allowLooseScheduled: Boolean): Reading? {
        if (text.isBlank()) return null
        if (isOfferScheduled(text, allowLooseScheduled)) {
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

    private fun isOfferScheduled(text: String, allowLooseScheduled: Boolean): Boolean {
        val lower = text.lowercase()
        if (looksLikeOffersListDump(lower) && !scheduledPhrases.any { lower.contains(it) }) {
            return false
        }
        if (scheduledPhrases.any { lower.contains(it) }) return true
        if (!allowLooseScheduled) return false
        // Solo en toast/banner cortos: "scheduled" + offer/block.
        if (text.length > 280) return false
        return lower.contains("scheduled") &&
            (lower.contains("offer") || lower.contains("block") || lower.contains("oferta") ||
                lower.contains("bloque"))
    }

    private fun looksLikeOffersListDump(lower: String): Boolean {
        if (lower.length < 80) return false
        var hits = 0
        for (m in offersListMarkers) {
            if (lower.contains(m)) hits++
        }
        return hits >= 2 || lower.contains("navigate up") && lower.contains("offers") &&
            (lower.contains("filter") || lower.contains("refresh"))
    }

    private fun extractFlexMessage(text: String, phrases: List<String>): String {
        val lower = text.lowercase()
        val matched = phrases.firstOrNull { lower.contains(it) }
            ?: return text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
                ?: text.take(120).trim()
        val idx = lower.indexOf(matched)
        if (idx < 0) return matched.replaceFirstChar { it.uppercase() }
        val start = text.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
        val line = text.substring(start, end).trim()
        return line.ifBlank { matched.replaceFirstChar { it.uppercase() } }.take(120)
    }
}
