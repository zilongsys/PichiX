package com.oceanlab.pichix.util

import com.oceanlab.pichix.data.AppSettings

object ScreenTextMatcher {
    fun matches(screenText: String, needle: String, mode: String, ignoreCase: Boolean): Boolean =
        TextMatcher.matches(screenText, needle, mode, ignoreCase)
}
