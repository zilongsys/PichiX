package com.oceanlab.pichix.util

import com.oceanlab.pichix.data.AppSettings

object ScreenTextMatcher {
    fun matches(screenText: String, needle: String, mode: String): Boolean {
        if (needle.isBlank()) return true
        return when (mode) {
            AppSettings.SCREEN_MATCH_EXACT -> matchesExact(screenText, needle)
            else -> screenText.contains(needle)
        }
    }

    /** Coincidencia exacta (sensible a mayúsculas): igualdad total o aparición del texto sin ser subcadena de otra palabra. */
    private fun matchesExact(screenText: String, needle: String): Boolean {
        if (screenText == needle) return true
        var idx = screenText.indexOf(needle)
        while (idx >= 0) {
            val beforeOk = idx == 0 || !screenText[idx - 1].isLetterOrDigit()
            val afterIdx = idx + needle.length
            val afterOk = afterIdx >= screenText.length || !screenText[afterIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            idx = screenText.indexOf(needle, idx + 1)
        }
        return false
    }
}
