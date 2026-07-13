package com.oceanlab.pichix.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexMessageHub
import com.oceanlab.pichix.util.FlexAlertDispatcher

/**
 * Lee notificaciones de la app Flex configurada: reglas de alerta (sonido) y observadores del bot.
 */
class FlexNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val targets = com.oceanlab.pichix.data.MonitorPackages.resolve(this).toSet()
        if (targets.isNotEmpty() && sbn.packageName !in targets) return

        val text = buildNotificationText(sbn).trim()
        if (text.isBlank()) return

        val settings = AppSettings(this)
        FlexAlertDispatcher.onFlexText(
            context = this,
            settings = settings,
            text = text,
            source = FlexMessageHub.Source.NOTIFICATION,
            dedupSuffix = sbn.key,
        )
    }

    private fun buildNotificationText(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val extras = n.extras
        val parts = listOfNotNull(
            extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString(),
            n.tickerText?.toString(),
        )
        return parts.joinToString(" ")
    }
}
