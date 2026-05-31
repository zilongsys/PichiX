package com.oceanlab.pichix.service

import android.content.Context
import com.oceanlab.pichix.data.AppSettings

object BotServiceCoordinator {

    @Volatile
    var isForegroundServiceActive: Boolean = false
        private set

    fun onForegroundServiceStarted() {
        isForegroundServiceActive = true
    }

    fun onForegroundServiceStopped() {
        isForegroundServiceActive = false
    }

    fun syncForegroundService(context: Context) {
        val settings = AppSettings(context)
        if (settings.isBotEnabled) {
            PichixForegroundService.start(context)
            PichixAccessibilityService.syncEngine(context)
        } else if (isForegroundServiceActive) {
            PichixForegroundService.stop(context)
            PichixAccessibilityService.notifyBotDisabled()
        }
        OverlayService.sync(context)
    }
}
