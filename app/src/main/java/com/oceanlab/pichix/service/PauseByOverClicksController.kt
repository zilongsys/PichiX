package com.oceanlab.pichix.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.util.AlertManager

/**
 * Pausa el bot al detectar una notificación con texto configurado; reanuda tras N minutos.
 */
object PauseByOverClicksController {

    private const val TAG = "PauseByOverClicks"
    private val handler = Handler(Looper.getMainLooper())
    private var resumeRunnable: Runnable? = null

    fun onNotification(context: Context, notificationText: String) {
        val settings = AppSettings(context)
        if (!settings.pauseByOverClicksEnabled) return
        if (!settings.isBotEnabled) return
        val needle = settings.pauseByOverClicksMatchText.trim()
        if (needle.isBlank()) return
        if (!notificationText.contains(needle, ignoreCase = true)) return
        if (PichixAccessibilityService.pausedAfterAccept) {
            rescheduleResume(context, settings)
            return
        }

        PichixAccessibilityService.pausedAfterAccept = true
        val lbm = LocalBroadcastManager.getInstance(context)
        lbm.sendBroadcast(Intent(PichixAccessibilityService.BOT_PAUSED))
        lbm.sendBroadcast(Intent(PichixAccessibilityService.BOT_STATE_CHANGED))
        PichixForegroundService.refreshNotification(context)

        AlertManager(context).playSoundOnce(settings.pauseByOverClicksPauseSoundUri)
        Log.i(TAG, "Bot pausado por notificación: contiene \"$needle\"")
        rescheduleResume(context, settings)
    }

    fun cancelScheduledResume() {
        resumeRunnable?.let { handler.removeCallbacks(it) }
        resumeRunnable = null
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
            Log.i(TAG, "Bot reanudado tras $minutes min")
        }
        resumeRunnable = runnable
        handler.postDelayed(runnable, delayMs)
        Log.d(TAG, "Reanudación programada en $minutes min")
    }
}
