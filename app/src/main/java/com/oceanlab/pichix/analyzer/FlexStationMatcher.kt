package com.oceanlab.pichix.analyzer

import com.oceanlab.pichix.data.FlexStationScope
import com.oceanlab.pichix.data.FlexTariffRule

object FlexStationMatcher {

    fun matches(stationName: String, rule: FlexTariffRule): Boolean =
        when (rule.stationScope) {
            FlexStationScope.GLOBAL -> true
            FlexStationScope.SPECIFIC -> matchesPattern(stationName, rule.stationPattern)
            FlexStationScope.GROUP -> {
                val patterns = rule.stationPattern.split(',', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                patterns.isNotEmpty() && patterns.any { matchesPattern(stationName, it) }
            }
        }

    fun matchesPattern(stationName: String, pattern: String): Boolean {
        val token = pattern.trim()
        if (token.isEmpty()) return false
        if (stationName.contains(token, ignoreCase = true)) {
            if (token.startsWith("#")) {
                val idx = stationName.indexOf(token, ignoreCase = true)
                return idx >= 0 && (idx + token.length >= stationName.length ||
                    !stationName[idx + token.length].isLetterOrDigit())
            }
            return true
        }
        val tokens = stationName.split(Regex("[\\s,;]+"))
        if (tokens.any { it.equals(token, ignoreCase = true) }) return true
        return false
    }
}
