package com.oceanlab.pichix.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexAlertRulesStore
import com.oceanlab.pichix.data.FlexState
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.util.AlertManager

/**
 * Lee notificaciones de la app Flex configurada: reglas de alerta (sonido) y observadores del bot.
 */
class FlexNotificationListenerService : NotificationListenerService() {

    private val recentlyTriggered = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val targets = MonitorPackages.resolve(this).toSet()
        if (targets.isNotEmpty() && sbn.packageName !in targets) return

        val text = buildNotificationText(sbn).trim()
        if (text.isBlank()) return

        val settings = AppSettings(this)
        if (settings.flexAlertsEnabled) {
            handleUserAlertRules(settings, sbn, text)
        }
        handleFlexObservers(settings, text)
    }

    private fun handleUserAlertRules(
        settings: AppSettings,
        sbn: StatusBarNotification,
        text: String,
    ) {
        val now = System.currentTimeMillis()
        FlexAlertRulesStore.load(settings)
            .asSequence()
            .filter { it.enabled && it.matchText.isNotBlank() }
            .firstOrNull { rule ->
                text.contains(rule.matchText.trim(), ignoreCase = true) &&
                    !wasRecentlyTriggered(rule.id, sbn.key, now)
            }
            ?.let { rule ->
                recentlyTriggered["${rule.id}|${sbn.key}"] = now
                pruneRecent(now)
                Log.d(TAG, "Alerta Flex: '${rule.displayName()}' → '${rule.matchText}'")
                AlertManager(this).playFlexNotificationAlert(rule.soundUri, rule.repeatCount)
                postObserverEvent("Alerta: ${rule.displayName()}")
            }
    }

    private fun handleFlexObservers(settings: AppSettings, text: String) {
        val lower = text.lowercase()
        when {
            lower.contains("reserved") || lower.contains("reservad") -> {
                FlexState.key2 = true
                postObserverEvent("Notificación: oferta reservada")
                if (settings.autoPauseOnReservedNotification && settings.isBotEnabled) {
                    PichixAccessibilityService.pausedAfterAccept = true
                    LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(Intent(PichixAccessibilityService.BOT_PAUSED))
                    LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(Intent(PichixAccessibilityService.BOT_STATE_CHANGED))
                }
            }
            lower.contains("no longer available") ||
                lower.contains("not available") ||
                lower.contains("unavailable") -> {
                FlexState.key3 = true
                postObserverEvent("Notificación: bloque no disponible")
            }
            lower.contains("scheduled") -> {
                FlexState.key1 = true
                postObserverEvent("Notificación: bloque programado")
            }
        }
    }

    private fun buildNotificationText(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val extras = n.extras
        val parts = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            n.tickerText?.toString(),
        )
        return parts.joinToString(" ")
    }

    private fun wasRecentlyTriggered(ruleId: String, notificationKey: String, now: Long): Boolean {
        val key = "$ruleId|$notificationKey"
        val last = recentlyTriggered[key] ?: return false
        return now - last < DEDUP_MS
    }

    private fun pruneRecent(now: Long) {
        val iter = recentlyTriggered.entries.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > DEDUP_MS) iter.remove()
        }
    }

    private fun postObserverEvent(message: String) {
        val intent = Intent(PichixAccessibilityService.OBSERVER_EVENT).apply {
            putExtra("message", message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "FlexNotifAlerts"
        private const val DEDUP_MS = 30_000L
    }
}
