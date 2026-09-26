package com.oceanlab.pichix.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.analyzer.OfferListDetailMatcher
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexAlertRulesStore
import com.oceanlab.pichix.data.FlexMessageHub
import com.oceanlab.pichix.data.FlexState
import com.oceanlab.pichix.service.PichixAccessibilityService
import com.oceanlab.pichix.service.PauseByOverClicksController

/**
 * Evalúa reglas de alertas según el origen del texto (notificación vs mensaje in-app).
 */
object FlexAlertDispatcher {

    private const val TAG = "FlexAlertDispatch"
    private const val DEDUP_MS = 30_000L

    private val recentTriggers = mutableMapOf<String, Long>()
    private var lastBuiltInScheduledAtMs = 0L

    fun onFlexText(
        context: Context,
        settings: AppSettings,
        text: String,
        source: FlexMessageHub.Source,
        dedupSuffix: String? = null,
        /** false = solo registrar en hub (durante flujo de toma; el outcome decide pausa/llamada). */
        allowSideEffects: Boolean = true,
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        when (source) {
            FlexMessageHub.Source.NOTIFICATION -> FlexMessageHub.recordNotification(trimmed)
            FlexMessageHub.Source.IN_APP -> FlexMessageHub.recordInApp(trimmed)
        }

        if (!allowSideEffects) {
            // Aun sin side-effects, avisar al motor si es toast de programado (reclasificar late recovery).
            if (isScheduledMessage(trimmed)) {
                PichixAccessibilityService.onScheduledToastSeen(trimmed)
            }
            return
        }

        PauseByOverClicksController.onNotification(context, trimmed)
        handleBuiltInObservers(context, settings, trimmed, source)

        if (!settings.flexAlertsEnabled) return

        val now = System.currentTimeMillis()
        val suffix = dedupSuffix ?: trimmed.take(96)
        val scheduled = isScheduledMessage(trimmed)
        FlexAlertRulesStore.load(settings)
            .asSequence()
            .filter { it.enabled && it.matchesSource(source) && it.matchesNotificationText(trimmed) }
            .forEach { rule ->
                val key = "${rule.id}|${source.name}|$suffix"
                if (wasRecentlyTriggered(key, now)) return@forEach
                markTriggered(key, now)
                Log.d(TAG, "Alerta [${source.name}] '${rule.displayName()}' → ${rule.matchSummary()}")
                AlertManager(context).playFlexNotificationAlert(rule.soundUri, rule.repeatCount)
                if (rule.callOnMatch) {
                    if (scheduled) {
                        // Pausa+llamada las maneja el flujo de toma / late recovery.
                        PichixAccessibilityService.onScheduledToastSeen(trimmed)
                    } else {
                        CallOnBlockHelper.maybeCall(
                            context,
                            settings,
                            "alerta ${source.name.lowercase()}: ${rule.displayName()}",
                            skipSound = true,
                        )
                    }
                }
                postObserverEvent(context, "Alerta (${sourceLabel(source)}): ${rule.displayName()}")
            }
    }

    private fun handleBuiltInObservers(
        context: Context,
        settings: AppSettings,
        text: String,
        source: FlexMessageHub.Source,
    ) {
        val lower = text.lowercase()
        when {
            lower.contains("reserved") || lower.contains("reservad") -> {
                FlexState.key2 = true
                postObserverEvent(context, "${sourcePrefix(source)}Oferta reservada")
                // «Someone else reserved that block» es PERDIDA de toma, no pausa por reserved.
                val isTakeMissReserved =
                    OfferListDetailMatcher.isBlockUnavailable(text) ||
                        lower.contains("someone else") ||
                        lower.contains("try reserving another")
                if (settings.autoPauseOnReservedNotification &&
                    settings.isBotEnabled &&
                    !isTakeMissReserved &&
                    !PichixAccessibilityService.isOutcomeWatchingPublic()
                ) {
                    PichixAccessibilityService.pausedAfterAccept = true
                    LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(Intent(PichixAccessibilityService.BOT_PAUSED))
                    LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(Intent(PichixAccessibilityService.BOT_STATE_CHANGED))
                }
            }
            OfferListDetailMatcher.isBlockUnavailable(text) -> {
                FlexState.key3 = true
                postObserverEvent(context, "${sourcePrefix(source)}Bloque no disponible")
            }
            isScheduledMessage(text) -> {
                val now = System.currentTimeMillis()
                if (now - lastBuiltInScheduledAtMs < DEDUP_MS) {
                    PichixAccessibilityService.onScheduledToastSeen(text)
                    return
                }
                lastBuiltInScheduledAtMs = now
                FlexState.key1 = true
                postObserverEvent(context, "${sourcePrefix(source)}Bloque programado")
                PichixAccessibilityService.onScheduledToastSeen(text)
                // No llamar aquí: completeAcceptedTake / when_accepted / alerta Tomado vía onScheduledToastSeen.
            }
        }
    }

    private fun isScheduledMessage(text: String): Boolean =
        com.oceanlab.pichix.analyzer.FlexTakeOutcomeReader.read(text).result ==
            com.oceanlab.pichix.analyzer.FlexTakeOutcomeReader.Result.SCHEDULED

    private fun sourcePrefix(source: FlexMessageHub.Source): String =
        when (source) {
            FlexMessageHub.Source.NOTIFICATION -> "Notificación: "
            FlexMessageHub.Source.IN_APP -> "Mensaje Flex: "
        }

    fun sourceLabel(source: FlexMessageHub.Source): String = when (source) {
        FlexMessageHub.Source.NOTIFICATION -> "notificación"
        FlexMessageHub.Source.IN_APP -> "mensaje Flex"
    }

    private fun postObserverEvent(context: Context, message: String) {
        val intent = Intent(PichixAccessibilityService.OBSERVER_EVENT).apply {
            putExtra("message", message)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    private fun wasRecentlyTriggered(key: String, now: Long): Boolean {
        val last = recentTriggers[key] ?: return false
        return now - last < DEDUP_MS
    }

    private fun markTriggered(key: String, now: Long) {
        recentTriggers[key] = now
        val cutoff = now - DEDUP_MS
        val iter = recentTriggers.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value < cutoff) iter.remove()
        }
    }
}
