package com.oceanlab.pichix.data

/** Código de estación: texto entre paréntesis, sin los paréntesis. */
object StationCode {

    fun code(station: String): String {
        val raw = station.trim()
        if (raw.isBlank() || raw == "—") return "?"
        val open = raw.indexOf('(')
        val close = raw.lastIndexOf(')')
        if (open >= 0 && close > open) {
            return raw.substring(open + 1, close).trim().ifBlank { "?" }
        }
        return "?"
    }
}
