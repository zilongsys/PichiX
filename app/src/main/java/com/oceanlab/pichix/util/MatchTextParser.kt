package com.oceanlab.pichix.util

object MatchTextParser {
    /** Separa por líneas y por comas; elimina vacíos y duplicados (orden preservado). */
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        raw.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { seen.add(it) }
        return seen.toList()
    }

    fun formatForEdit(texts: List<String>): String = texts.joinToString("\n")
}
