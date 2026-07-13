package com.oceanlab.pichix.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.BotEventLog
import com.oceanlab.pichix.util.AlertManager
import com.oceanlab.pichix.util.TextMatcher

/**
 * Pausa el bot al detectar texto de bloqueo flotante en pantalla Flex o en notificación.
 */
object PauseByOverClicksController {

    private const val TAG = "PauseByOverClicks"
    private val handler = Handler(Looper.getMainLooper())
    private var resumeRunnable: Runnable? = null

    /** Frases por defecto si el campo de texto está vacío (cartel en pantalla / aviso Flex). */
    private val DEFAULT_BLOCK_PHRASES: List<String>
        get() = FlexBlockingPhrases.FLEX_THROTTLE_PHRASES + FlexBlockingPhrases.OTHER_BLOCK_PHRASES

    /** Evalúa texto dibujado en pantalla (banner flotante in-app, no notificación). */
    fun onScreenText(context: Context, screenText: String): Boolean {
        val settings = AppSettings(context)
        if (!settings.isBotEnabled) return false
        if (PichixAccessibilityService.pausedAfterAccept) return true

        FlexBlockingPhrases.findFlexThrottleNeedle(screenText)?.let { needle ->
            return triggerPause(context, settings, needle, "banner Flex")
        }

        if (!settings.pauseByOverClicksEnabled) return false
        val needle = findMatchingNeedle(screenText, settings) ?: return false
        return triggerPause(context, settings, needle, "pantalla")
    }

    /** Comprueba bloqueo sin pausar (p. ej. antes de un clic en el mismo tick). */
    fun wouldBlockClicks(context: Context, screenText: String): Boolean {
        if (PichixAccessibilityService.pausedAfterAccept) return true
        val settings = AppSettings(context)
        if (!settings.isBotEnabled) return false
        if (FlexBlockingPhrases.isFlexThrottleBanner(screenText)) return true
        if (!settings.pauseByOverClicksEnabled) return false
        return findMatchingNeedle(screenText, settings) != null
    }

    fun onNotification(context: Context, notificationText: String) {
        val settings = AppSettings(context)
        if (!settings.isBotEnabled || !settings.pauseByOverClicksEnabled) return
        val needle = findMatchingNeedle(notificationText, settings) ?: return
        triggerPause(context, settings, needle, "notificación")
    }

    fun cancelScheduledResume() {
        resumeRunnable?.let { handler.removeCallbacks(it) }
        resumeRunnable = null
    }

    private fun findMatchingNeedle(text: String, settings: AppSettings): String? {
        if (!settings.pauseByOverClicksEnabled) return null
        if (text.isBlank()) return null
        for (needle in needlesFromSettings(settings)) {
            if (TextMatcher.matches(
                    text,
                    needle,
                    settings.pauseByOverClicksMatchMode,
                    settings.pauseByOverClicksIgnoreCase,
                )
            ) {
                return needle
            }
        }
        return null
    }

    private fun needlesFromSettings(settings: AppSettings): List<String> {
        val raw = settings.pauseByOverClicksMatchText.trim()
        if (raw.isNotBlank()) {
            return raw.split(',', '\n', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return DEFAULT_BLOCK_PHRASES
    }

    private fun triggerPause(
        context: Context,
        settings: AppSettings,
        needle: String,
        source: String,
    ): Boolean {
        if (PichixAccessibilityService.pausedAfterAccept) {
            rescheduleResume(context, settings)
            return true
        }

        PichixAccessibilityService.pausedAfterAccept = true
        val lbm = LocalBroadcastManager.getInstance(context)
        lbm.sendBroadcast(Intent(PichixAccessibilityService.BOT_PAUSED))
        lbm.sendBroadcast(Intent(PichixAccessibilityService.BOT_STATE_CHANGED))
        PichixForegroundService.refreshNotification(context)

        AlertManager(context).playSoundOnce(settings.pauseByOverClicksPauseSoundUri)
        BotEventLog.log(context, BotEventLog.CAT_PAUSE, "Pausado ($source): «$needle»")
        Log.i(TAG, "Bot pausado por $source: contiene \"$needle\"")
        rescheduleResume(context, settings)
        return true
    }

    private fun rescheduleResume(context: Context, settings: AppSettings) {
        cancelScheduledResume()
        val minutes = settings.pauseByOverClicksResumeMinutes.coerceIn(1, 24 * 60)
        val delayMs = minutes * 60_000L
        val runnable = Runnable {
            resumeRunnable = null
            if (!PichixAccessibilityService.pausedAfterAccept) return@Runnable
            AlertManager(context).playSoundOnce(settings.pauseByOverClicksResumeSoundUri)
            PichixAccessibilityService.resumeFromPause(context)
            BotEventLog.log(context, BotEventLog.CAT_PAUSE, "Reanudado tras $minutes min (auto)")
            Log.i(TAG, "Bot reanudado tras $minutes min")
        }
        resumeRunnable = runnable
        handler.postDelayed(runnable, delayMs)
        Log.d(TAG, "Reanudación programada en $minutes min")
    }
}
