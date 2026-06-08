package com.oceanlab.pichix.data

/** Código corto de estación para tablas y filtros compactos. */
object StationCode {

    private val parenCode = Regex("\\(([A-Z0-9]{2,10})\\)")
    private val leadingCode = Regex("^([A-Z0-9]{2,10})")
    private val splitDelim = Regex("[\\s\\-–—|,]+")

    fun compact(station: String): String {
        val raw = station.trim()
        if (raw.isBlank() || raw == "—" || raw == "?") return "?"
        parenCode.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        val first = splitDelim.split(raw).firstOrNull()?.trim().orEmpty()
        if (first.matches(Regex("[A-Z0-9]{2,10}"))) return first
        leadingCode.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        return first.take(8).ifBlank { raw.take(8) }
    }
}
