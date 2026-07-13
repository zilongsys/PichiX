package com.oceanlab.pichix.service

/**
 * Textos del banner in-app de Flex (no notificación del sistema).
 * Ej.: «You've tapped too many times or for too long. Please try again later.»
 */
object FlexBlockingPhrases {

    /** Banner «demasiados toques» — siempre se detecta con el bot activo. */
    val FLEX_THROTTLE_PHRASES = listOf(
        "tapped too many times",
        "tapped too many",
        "too many tap",
        "too many taps",
        "too many click",
        "too many clicks",
        "clicked too quickly",
        "click too fast",
        "tap too fast",
        "slow down",
        "try again later",
        "demasiados clic",
        "demasiados toque",
        "demasiados toques",
        "has hecho demasiados",
        "demasiado tiempo",
        "inténtalo más tarde",
        "intentalo mas tarde",
    )

    val OTHER_BLOCK_PHRASES = listOf(
        "verification required",
        "verify you're",
        "verify you are",
        "captcha",
        "robot check",
        "not a robot",
        "puzzle",
    )

    fun findFlexThrottleNeedle(text: String, ignoreCase: Boolean = true): String? {
        if (text.isBlank()) return null
        val hay = if (ignoreCase) text.lowercase() else text
        return FLEX_THROTTLE_PHRASES.firstOrNull { needle ->
            val n = if (ignoreCase) needle.lowercase() else needle
            hay.contains(n)
        }
    }

    fun isFlexThrottleBanner(text: String): Boolean = findFlexThrottleNeedle(text) != null

    fun isAnyBlockingOverlay(text: String, ignoreCase: Boolean = true): Boolean {
        if (text.isBlank()) return false
        val hay = if (ignoreCase) text.lowercase() else text
        return (FLEX_THROTTLE_PHRASES + OTHER_BLOCK_PHRASES).any { hay.contains(it.lowercase()) }
    }
}
