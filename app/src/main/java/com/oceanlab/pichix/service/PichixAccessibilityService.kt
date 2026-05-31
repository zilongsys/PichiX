package com.oceanlab.pichix.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.analyzer.FlexGrabResult
import com.oceanlab.pichix.analyzer.FlexBlockOffer
import com.oceanlab.pichix.analyzer.FlexGrabberEvaluator
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexState
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus
import com.oceanlab.pichix.service.accessibility.FlexScreenReader
import com.oceanlab.pichix.ui.MainActivity

/**
 * Motor Flex: Terminator-Grabber, scroll, observers, pausa/reanudación y timer.
 */
class PichixAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PichixFlex"
        const val BOT_STATE_CHANGED = MainActivity.BOT_STATE_CHANGED
        const val BOT_PAUSED = MainActivity.BOT_PAUSED
        const val BOT_RESUMED = "com.oceanlab.pichix.BOT_RESUMED"
        const val OBSERVER_EVENT = "com.oceanlab.pichix.OBSERVER_EVENT"

        @Volatile var pausedAfterAccept = false

        @Volatile private var instance: PichixAccessibilityService? = null

        fun notifyBotDisabled() {
            instance?.stopEngine()
        }

        fun resumeFromPause(context: Context) {
            pausedAfterAccept = false
            syncEngine(context)
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(BOT_RESUMED))
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(BOT_STATE_CHANGED))
            PichixForegroundService.refreshNotification(context)
        }

        fun syncEngine(context: Context) {
            instance?.scheduleWork()
        }

        fun scrollDownManual() = instance?.performScrollDown()
        fun scrollUpManual() = instance?.performScrollUp()
        fun returnToOffers() = instance?.performReturnToOffers()
    }

    private lateinit var settings: AppSettings
    private lateinit var logger: OfferLogger
    private lateinit var reader: FlexScreenReader

    private val handler = Handler(Looper.getMainLooper())
    private var grabInFlight = false
    private var scrollInFlight = false
    private var lastOfferSignature = ""
    private var scrollDownEnd = false
    private var scrollUpEnd = false
    private var lastReturnMs = 0L

    private val grabLoopRunnable = Runnable { runGrabberTick() }
    private val timerRunnable = Runnable { runFlexTimerTick() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = AppSettings(this)
        logger = OfferLogger(this)
        reader = FlexScreenReader(this)
        Log.i(TAG, "Conectado — ${MonitorPackages.resolve(this).joinToString()}")
        scheduleWork()
    }

    override fun onDestroy() {
        stopEngine()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!settings.isBotEnabled || pausedAfterAccept) return
        val target = MonitorPackages.primaryTarget(this) ?: return
        if (event.packageName?.toString() != target) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handler.removeCallbacks(observerRunnable)
                handler.postDelayed(observerRunnable, 200)
            }
        }
    }

    private val observerRunnable = Runnable { runObservers() }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    private fun stopEngine() {
        handler.removeCallbacksAndMessages(null)
        grabInFlight = false
        scrollInFlight = false
        FlexState.resetScrollCycle()
        lastOfferSignature = ""
        scrollDownEnd = false
        scrollUpEnd = false
    }

    private fun scheduleWork() {
        handler.removeCallbacks(grabLoopRunnable)
        handler.removeCallbacks(timerRunnable)
        if (!settings.isBotEnabled) return
        val interval = settings.flexGrabIntervalMs
        handler.postDelayed(grabLoopRunnable, interval)
        handler.postDelayed(timerRunnable, FlexState.flexTimerSec * 1000L)
    }

    private fun runFlexTimerTick() {
        FlexState.counter++
        FlexState.timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        if (settings.isBotEnabled) {
            handler.postDelayed(timerRunnable, FlexState.flexTimerSec * 1000L)
        }
    }

    private fun runObservers() {
        val text = reader.readFullScreenText()
        val flags = reader.detectScreenFlags(text)

        when {
            flags.captcha -> postObserver("Captcha detectado")
            flags.blockUnavailable -> postObserver("Bloque no disponible en pantalla")
            flags.offerScheduled -> postObserver("Oferta programada en pantalla")
        }

        if (flags.captcha && settings.autoPauseOnCaptcha) {
            pausedAfterAccept = true
            postBotPaused()
        }

        if (settings.flexAutoReturnToOffers && !flags.onOffersList && !flags.captcha) {
            val now = System.currentTimeMillis()
            if (now - lastReturnMs > 3000L) {
                lastReturnMs = now
                performReturnToOffers()
            }
        }
    }

    private fun runGrabberTick() {
        scheduleWork()
        if (!settings.isBotEnabled || pausedAfterAccept || grabInFlight) return
        grabInFlight = true
        try {
            if (settings.flexOnlyRefresh) {
                reader.clickRefresh()
                return
            }

            val text = reader.readFullScreenText()
            val flags = reader.detectScreenFlags(text)
            if (flags.captcha) return

            val offers = reader.readOffersFromList()
            if (offers.isEmpty()) {
                maybeScrollList()
                return
            }

            for (offer in offers) {
                when (FlexGrabberEvaluator.evaluateListRow(offer, settings, text)) {
                    FlexGrabResult.ACCEPT, FlexGrabResult.SIMULATED_ACCEPT -> {
                        handleAccept(offer, settings.dryRunMode)
                        return
                    }
                    FlexGrabResult.REJECT -> {
                        if (settings.flexCancelBadBlocks) {
                            logger.log(
                                pay = offer.payText,
                                station = offer.stationText,
                                status = OfferStatus.REJECTED,
                                note = "Criterio grabber",
                            )
                        }
                    }
                    FlexGrabResult.SKIP -> Unit
                }
            }

            maybeScrollList()
        } finally {
            grabInFlight = false
        }
    }

    private fun handleAccept(offer: FlexBlockOffer, dryRun: Boolean) {
        if (dryRun) {
            logger.log(
                pay = offer.payText,
                station = offer.stationText,
                status = OfferStatus.SIMULATED,
                note = "Simulación",
            )
            postObserver("SIMULADA: ${offer.stationText} ${offer.payText}")
            return
        }

        reader.clickScheduleOnList(offer.index)
        handler.postDelayed({
            val details = reader.readBlockDetails()
            val detailResult = FlexGrabberEvaluator.evaluateDetailScreen(
                payRangeText = details["pay_range"].orEmpty(),
                timeWindowText = details["time_window"].orEmpty(),
                settings = settings,
                station = offer.stationText.ifBlank { details["station"].orEmpty() },
                screenText = reader.readFullScreenText(),
            )
            if (detailResult == FlexGrabResult.ACCEPT) {
                reader.clickScheduleOnDetail()
            }
            val accepted = detailResult == FlexGrabResult.ACCEPT
            logger.log(
                pay = offer.payText,
                station = offer.stationText.ifBlank { details["station"].orEmpty() },
                status = if (accepted) OfferStatus.ACCEPTED else OfferStatus.REJECTED,
                note = "Grabber detalle",
            )
            if (settings.autoPauseAfterAccept && accepted) {
                pausedAfterAccept = true
                postBotPaused()
            }
            broadcastState()
        }, 600)
    }

    private fun maybeScrollList() {
        if (settings.flexOnlyRefresh || scrollInFlight) return
        val sig = reader.offerListSignature()
        if (sig.isNotEmpty() && sig == lastOfferSignature) {
            FlexState.scrollNeeded = true
            when {
                !scrollDownEnd -> performScrollDown()
                !scrollUpEnd -> performScrollUp()
                else -> {
                    FlexState.resetScrollCycle()
                    scrollDownEnd = false
                    scrollUpEnd = false
                    lastOfferSignature = ""
                    postObserver("Ciclo de scroll completado — reiniciando")
                }
            }
        } else {
            lastOfferSignature = sig
            FlexState.scrollNeeded = false
        }
    }

    private fun performScrollDown() {
        if (scrollInFlight) return
        scrollInFlight = true
        FlexState.counterScroll++
        val before = reader.offerListSignature()
        reader.scrollDown()
        handler.postDelayed({
            val after = reader.offerListSignature()
            scrollDownEnd = before.isNotEmpty() && before == after
            if (scrollDownEnd) postObserver("Fin de lista (abajo)")
            scrollInFlight = false
        }, 700)
    }

    private fun performScrollUp() {
        if (scrollInFlight) return
        scrollInFlight = true
        val before = reader.offerListSignature()
        reader.scrollUp()
        handler.postDelayed({
            val after = reader.offerListSignature()
            scrollUpEnd = before.isNotEmpty() && before == after
            if (scrollUpEnd) postObserver("Fin de lista (arriba)")
            scrollInFlight = false
        }, 700)
    }

    private fun performReturnToOffers() {
        repeat(2) { reader.clickBack() }
        handler.postDelayed({ if (!pausedAfterAccept) runGrabberTick() }, 400)
    }

    private fun postObserver(message: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(OBSERVER_EVENT).putExtra("message", message),
        )
        Log.d(TAG, "Observer: $message")
    }

    private fun postBotPaused() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BOT_PAUSED))
        broadcastState()
    }

    private fun broadcastState() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BOT_STATE_CHANGED))
        PichixForegroundService.refreshNotification(this)
    }
}
