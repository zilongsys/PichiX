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
import com.oceanlab.pichix.data.PichiFileLog
import com.oceanlab.pichix.data.FlexState
import com.oceanlab.pichix.data.FlexTariffRulesStore
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus
import com.oceanlab.pichix.service.accessibility.FlexScreenReader
import com.oceanlab.pichix.service.accessibility.FlexUiDumper
import com.oceanlab.pichix.ui.MainActivity

/**
 * Motor Flex: Terminator-Grabber, scroll, observers, pausa/reanudación y timer.
 * El bucle de acción no usa filtros de primer plano (v0.1.7); los switches de Config
 * afectan alertas/notificaciones, no bloquean [runGrabberTick].
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
    private var lastGrabEvalLogMs = 0L

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
        settings = AppSettings(this)
        val target = MonitorPackages.primaryTarget(this) ?: return
        val pkg = event.packageName?.toString().orEmpty()
        if (settings.debugLogEnabled && pkg == target) {
            rootInActiveWindow?.let { root ->
                try {
                    FlexUiDumper.maybeDumpPeriodic(root, pkg)
                } finally {
                    try {
                        root.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (!settings.isBotEnabled || pausedAfterAccept) return
        if (pkg != target) return
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
        handler.postDelayed(grabLoopRunnable, settings.nextGrabDelayMs())
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
        settings = AppSettings(this)
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
        settings = AppSettings(this)
        scheduleWork()
        if (!settings.isBotEnabled || pausedAfterAccept || grabInFlight) return
        grabInFlight = true
        try {
            val text = reader.readFullScreenText()
            if (settings.flexClickRefreshEnabled &&
                reader.screenMatchesForClick(
                    settings.flexClickScreenText,
                    text,
                    settings.flexClickScreenMatchMode,
                    settings.flexClickScreenIgnoreCase,
                )
            ) {
                val target = MonitorPackages.primaryTarget(this)
                val logUi = settings.debugLogEnabled || settings.fileLogEnabled
                if (logUi) {
                    reader.withActiveRoot { root ->
                        FlexUiDumper.dumpOnRefreshClick(root, target, "antes")
                    }
                }
                val clicked = reader.clickTargetButton(
                    settings.flexRefreshButtonText,
                    settings.flexRefreshButtonMatchMode,
                    settings.flexRefreshButtonIgnoreCase,
                )
                if (!clicked) {
                    postObserver("Clic: no se encontró «${settings.flexRefreshButtonText}»")
                }
                if (logUi) {
                    handler.postDelayed({
                        reader.withActiveRoot { root ->
                            FlexUiDumper.dumpOnRefreshClick(root, target, "despues")
                        }
                    }, 450)
                }
            }

            warnIfTariffRulesMisconfigured()

            val flags = reader.detectScreenFlags(text)
            if (flags.captcha) return

            val offers = reader.readOffersFromList()
            if (settings.debugLogEnabled && offers.isNotEmpty()) {
                offers.forEachIndexed { i, o ->
                    Log.i(
                        FlexUiDumper.DEBUG_TAG,
                        "Oferta[$i] ${o.stationText} | ${o.timeText} | ${o.durationHours ?: "?"}h | ${o.payText} | \$${o.hourlyRate ?: 0}/h",
                    )
                }
            }
            if (offers.isEmpty()) {
                logGrabEvalThrottled("Lista sin ofertas parseadas (revisa ids offer_pay)")
                maybeScrollList()
                return
            }

            for (offer in offers) {
                when (FlexGrabberEvaluator.evaluateListRow(offer, settings, text)) {
                    FlexGrabResult.ACCEPT, FlexGrabResult.SIMULATED_ACCEPT -> {
                        postObserver(
                            "Tomando: ${offer.stationText} ${offer.payText} " +
                                "(${offer.hourlyRate?.let { "%.1f".format(it) } ?: "?"} \$/h)",
                        )
                        handleAccept(offer)
                        return
                    }
                    FlexGrabResult.REJECT -> {
                        logGrabEvalThrottled(
                            "No cumple regla: ${offer.stationText} ${offer.payText} " +
                                "(${offer.hourlyRate?.let { "%.1f".format(it) } ?: "?"} \$/h)",
                        )
                        if (settings.flexCancelBadBlocks) {
                            logger.log(
                                pay = offer.payText,
                                station = offer.stationText,
                                status = OfferStatus.REJECTED,
                                note = "Criterio grabber",
                            )
                        }
                    }
                    FlexGrabResult.SKIP -> {
                        logGrabEvalThrottled(
                            "Datos incompletos: ${offer.stationText} ${offer.payText}",
                        )
                    }
                }
            }

            maybeScrollList()
        } finally {
            grabInFlight = false
        }
    }

    private fun handleAccept(offer: FlexBlockOffer) {
        val simulation = settings.dryRunMode
        val shouldSchedule = settings.flexAutoAccept && !simulation

        if (!reader.clickOfferCardAtIndex(offer.index)) {
            postObserver("No se pudo abrir la oferta en la lista")
            return
        }

        handler.postDelayed({
            val details = reader.readBlockDetails()
            val station = offer.stationText.ifBlank { details["station"].orEmpty() }
            val screenText = reader.readFullScreenText()

            if (!shouldSchedule) {
                val note = if (simulation) "Simulación — detalle sin Schedule" else "Detalle abierto — sin aceptar automático"
                logger.log(
                    pay = offer.payText,
                    station = station,
                    status = OfferStatus.SIMULATED,
                    note = note,
                )
                postObserver(
                    if (simulation) {
                        "SIMULACIÓN: ${offer.stationText} ${offer.payText} (Offer Details, sin Schedule)"
                    } else {
                        "Detalle: ${offer.stationText} — revisa y pulsa Schedule manualmente"
                    },
                )
                finishBlockTakeFlow()
                return@postDelayed
            }

            val detailResult = FlexGrabberEvaluator.evaluateDetailScreen(
                payRangeText = details["pay_range"].orEmpty(),
                timeWindowText = details["time_window"].orEmpty(),
                settings = settings,
                station = station,
                screenText = screenText,
            )
            if (detailResult != FlexGrabResult.ACCEPT) {
                logger.log(
                    pay = offer.payText,
                    station = station,
                    status = OfferStatus.REJECTED,
                    note = "Detalle no cumple criterios",
                )
                postObserver("Rechazada en detalle: $station")
                finishBlockTakeFlow()
                return@postDelayed
            }

            val scheduled = reader.clickScheduleOnDetail()
            logger.log(
                pay = offer.payText,
                station = station,
                status = if (scheduled) OfferStatus.ACCEPTED else OfferStatus.REJECTED,
                note = if (scheduled) "Schedule pulsado" else "No se encontró Schedule",
            )
            postObserver(
                if (scheduled) {
                    "ACEPTADA: $station ${offer.payText}"
                } else {
                    "Offer Details: no se encontró botón Schedule"
                },
            )
            finishBlockTakeFlow()
        }, 650)
    }

    /** Pausa el bot una vez tras abrir/aceptar un bloque (lista → detalle → Schedule opcional). */
    private fun warnIfTariffRulesMisconfigured() {
        val rules = FlexTariffRulesStore.load(settings)
        if (rules.any { it.enabled } && !settings.usesFlexDetailedTariff()) {
            logGrabEvalThrottled(
                "Tienes reglas guardadas: activa Tarifas → modo Detallado (Reglas), no Clásico",
            )
        }
    }

    private fun logGrabEvalThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastGrabEvalLogMs < 12_000L) return
        lastGrabEvalLogMs = now
        postObserver(message)
        Log.d(TAG, message)
    }

    private fun finishBlockTakeFlow() {
        pausedAfterAccept = true
        postBotPaused()
        broadcastState()
    }

    private fun maybeScrollList() {
        if (settings.flexDisableListScroll || scrollInFlight) return
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
