package com.oceanlab.pichix.data

/**
 * Mensajes recientes de Flex: notificación del sistema o texto flotante/banner in-app.
 */
object FlexMessageHub {

    enum class Source {
        /** Notificación de la barra de estado (NotificationListener). */
        NOTIFICATION,
        /** Banner, toast o texto flotante dentro de Flex (accesibilidad). */
        IN_APP,
    }

    @Volatile
    private var lastNotificationText: String = ""

    @Volatile
    private var lastNotificationAtMs: Long = 0L

    @Volatile
    private var lastInAppText: String = ""

    @Volatile
    private var lastInAppAtMs: Long = 0L

    fun recordNotification(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        lastNotificationText = t
        lastNotificationAtMs = System.currentTimeMillis()
    }

    fun recordInApp(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        lastInAppText = t
        lastInAppAtMs = System.currentTimeMillis()
    }

    /** Limpia toasts previos al iniciar confirmación Schedule (evita falsos ACEPTADA/PERDIDA). */
    fun clearRecent() {
        lastNotificationText = ""
        lastNotificationAtMs = 0L
        lastInAppText = ""
        lastInAppAtMs = 0L
    }

    fun recentNotificationText(withinMs: Long = 20_000L, now: Long = System.currentTimeMillis()): String {
        if (lastNotificationText.isBlank()) return ""
        return if (now - lastNotificationAtMs <= withinMs) lastNotificationText else ""
    }

    fun recentInAppText(withinMs: Long = 20_000L, now: Long = System.currentTimeMillis()): String {
        if (lastInAppText.isBlank()) return ""
        return if (now - lastInAppAtMs <= withinMs) lastInAppText else ""
    }

    /**
     * Toast/banner más reciente entre notificación e in-app (para confirmar Schedule).
     */
    fun recentFlexToastText(withinMs: Long = 20_000L, now: Long = System.currentTimeMillis()): String {
        val notifOk = lastNotificationText.isNotBlank() && now - lastNotificationAtMs <= withinMs
        val inAppOk = lastInAppText.isNotBlank() && now - lastInAppAtMs <= withinMs
        return when {
            notifOk && inAppOk ->
                if (lastInAppAtMs >= lastNotificationAtMs) lastInAppText else lastNotificationText
            inAppOk -> lastInAppText
            notifOk -> lastNotificationText
            else -> ""
        }
    }
}
