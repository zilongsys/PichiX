package com.oceanlab.pichix.util

import com.oceanlab.pichix.data.AppSettings

object TextMatcher {
    fun matches(
        haystack: String,
        needle: String,
        matchMode: String = AppSettings.TEXT_MATCH_CONTAINS,
        ignoreCase: Boolean = true,
    ): Boolean {
        if (needle.isBlank()) return true
        return when (matchMode) {
            AppSettings.TEXT_MATCH_EXACT -> matchesExactToken(haystack, needle, ignoreCase)
            else -> haystack.contains(needle, ignoreCase = ignoreCase)
        }
    }

    /** Coincidencia exacta: igualdad total o aparición como token (no mitad de otra palabra). */
    private fun matchesExactToken(haystack: String, needle: String, ignoreCase: Boolean): Boolean {
        if (haystack.equals(needle, ignoreCase)) return true
        var idx = haystack.indexOf(needle, ignoreCase = ignoreCase)
        while (idx >= 0) {
            val beforeOk = idx == 0 || !haystack[idx - 1].isLetterOrDigit()
            val afterIdx = idx + needle.length
            val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            idx = haystack.indexOf(needle, startIndex = idx + 1, ignoreCase = ignoreCase)
        }
        return false
    }
}
