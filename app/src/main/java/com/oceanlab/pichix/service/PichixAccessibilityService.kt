package com.oceanlab.pichix.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.analyzer.FlexOfferSelector
import com.oceanlab.pichix.analyzer.FlexGrabResult
import com.oceanlab.pichix.analyzer.FlexBlockOffer
import com.oceanlab.pichix.analyzer.FlexGrabberEvaluator
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.PichiFileLog
import com.oceanlab.pichix.data.FlexState
import com.oceanlab.pichix.data.FlexTariffRulesStore
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.OfferLogEntry
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus
import com.oceanlab.pichix.service.accessibility.FlexForegroundGate
import com.oceanlab.pichix.service.accessibility.FlexListScroller
import com.oceanlab.pichix.service.accessibility.FlexScreenReader
import com.oceanlab.pichix.service.accessibility.FlexUiDumper
import com.oceanlab.pichix.ui.MainActivity

/**
 * Motor Flex: Terminator-Grabber, scroll, observers, pausa/reanudación y timer.
 * Con [AppSettings.flexOnlyWhenForeground] (por defecto ON), el motor solo actúa si Flex
 * está en primer plano ([FlexForegroundGate]).
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
    private var scrollSigsBefore = ""
    /** Dirección actual del ciclo de scroll (estilo MakiX: invierte al llegar al fin). */
    private var scrollingDown = true
    private var lastReturnMs = 0L
    private var lastGrabEvalLogMs = 0L

    private val grabLoopRunnable = Runnable { runGrabberTick() }
    private val timerRunnable = Runnable { runFlexTimerTick() }
    private var pendingScrollWasDown = true
    private val scrollSettleRunnable = Runnable { finishScrollSettle() }

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
        FlexForegroundGate.onAccessibilityEvent(event, MonitorPackages.resolve(this).toSet())
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
        cancelPendingMotorActions()
        grabInFlight = false
        FlexState.resetScrollCycle()
        scrollSigsBefore = ""
        scrollingDown = true
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
        if (!isMotorForegroundAllowed()) return
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
        if (!isMotorForegroundAllowed()) return
        grabInFlight = true
        try {
            val text = reader.readFullScreenText()
            if (settings.flexClickRefreshEnabled) {
                val screenOk = reader.screenMatchesForClick(
                    settings.flexClickScreenText,
                    text,
                    settings.flexClickScreenMatchMode,
                    settings.flexClickScreenIgnoreCase,
                )
                if (!screenOk && settings.flexClickScreenText.isNotBlank()) {
                    logGrabEvalThrottled(
                        "Clic omitido: pantalla no coincide con «${settings.flexClickScreenText}» " +
                            "(modo ${settings.flexClickScreenMatchMode})",
                    )
                } else if (screenOk) {
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
                } else if (settings.debugLogEnabled) {
                    postObserver("Clic Refresh (siguiente en ${settings.nextGrabDelayMs() / 1000}s)")
                }
                if (logUi) {
                    handler.postDelayed({
                        reader.withActiveRoot { root ->
                            FlexUiDumper.dumpOnRefreshClick(root, target, "despues")
                        }
                    }, 450)
                }
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

            val evaluated = offers.map { offer ->
                FlexOfferSelector.EvaluatedOffer(
                    offer,
                    FlexGrabberEvaluator.evaluateListRow(offer, settings, text),
                )
            }
            val winner = FlexOfferSelector.pickAcceptable(evaluated, settings)
            if (winner != null) {
                postObserver(FlexOfferSelector.selectionSummary(winner, evaluated, settings))
                handleAccept(winner)
                return
            }

            for ((offer, result) in evaluated) {
                when (result) {
                    FlexGrabResult.REJECT -> {
                        logGrabEvalThrottled(
                            "No cumple regla: ${offer.stationText} ${offer.payText} " +
                                "(${offer.hourlyRate?.let { "%.1f".format(it) } ?: "?"} \$/h)",
                        )
                        if (settings.flexCancelBadBlocks) {
                            logger.log(offer.toLogEntry(OfferStatus.REJECTED, "Criterio grabber"))
                        }
                    }
                    FlexGrabResult.SKIP -> {
                        logGrabEvalThrottled(
                            "Datos incompletos: ${offer.stationText} ${offer.payText}",
                        )
                    }
                    else -> Unit
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
            if (!isMotorForegroundAllowed()) {
                finishBlockTakeFlow()
                return@postDelayed
            }
            val details = reader.readBlockDetails()
            val station = offer.stationText.ifBlank { details["station"].orEmpty() }
            val screenText = reader.readFullScreenText()

            if (!shouldSchedule) {
                val note = if (simulation) "Simulación — detalle sin Schedule" else "Detalle abierto — sin aceptar automático"
                logger.log(
                    offer.toLogEntry(
                        if (simulation) OfferStatus.SIMULATED else OfferStatus.SIMULATED,
                        note,
                    ),
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
                logger.log(offer.toLogEntry(OfferStatus.REJECTED, "Detalle no cumple criterios", station))
                postObserver("Rechazada en detalle: $station")
                finishBlockTakeFlow()
                return@postDelayed
            }

            val scheduled = reader.clickScheduleOnDetail()
            logger.log(
                offer.toLogEntry(
                    if (scheduled) OfferStatus.ACCEPTED else OfferStatus.REJECTED,
                    if (scheduled) "Schedule pulsado" else "No se encontró Schedule",
                    station,
                ),
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
        if (!settings.flexAutoScrollEnabled || scrollInFlight) return
        if (!isMotorForegroundAllowed()) return
        val screen = reader.readFullScreenText()
        if (!reader.detectScreenFlags(screen).onOffersList) return
        performDirectionalScroll(scrollingDown)
    }

    private fun isMotorForegroundAllowed(): Boolean {
        val target = MonitorPackages.primaryTarget(this) ?: return false
        val allowed = FlexForegroundGate.allowMotor(this, settings, target) {
            reader.isFlexForegroundUi()
        }
        if (!allowed) cancelPendingMotorActions()
        return allowed
    }

    private fun cancelPendingMotorActions() {
        handler.removeCallbacks(scrollSettleRunnable)
        scrollInFlight = false
    }

    private fun performDirectionalScroll(down: Boolean) {
        if (scrollInFlight || !isMotorForegroundAllowed()) return
        scrollInFlight = true
        FlexState.counterScroll++
        scrollSigsBefore = reader.offerListSignature()
        pendingScrollWasDown = down
        val onScrollDone: (Boolean) -> Unit = { dispatched ->
            if (!dispatched) {
                scrollInFlight = false
            } else {
                handler.removeCallbacks(scrollSettleRunnable)
                handler.postDelayed(scrollSettleRunnable, FlexListScroller.SETTLE_MS)
            }
        }
        if (down) reader.scrollDown(onFinished = onScrollDone) else reader.scrollUp(onFinished = onScrollDone)
    }

    private fun finishScrollSettle() {
        if (!isMotorForegroundAllowed()) {
            scrollInFlight = false
            return
        }
        val down = pendingScrollWasDown
        val after = reader.offerListSignature()
        val moved = scrollSigsBefore.isNotEmpty() && scrollSigsBefore != after
        if (!moved) {
            if (down) {
                scrollingDown = false
                postObserver("Fin de lista (abajo) → scroll arriba")
            } else {
                scrollingDown = true
                postObserver("Fin de lista (arriba) → scroll abajo")
            }
            FlexState.scrollNeeded = true
        } else {
            FlexState.scrollNeeded = false
        }
        scrollInFlight = false
    }

    private fun performScrollDown() = performDirectionalScroll(down = true)

    private fun performScrollUp() = performDirectionalScroll(down = false)

    private fun performReturnToOffers() {
        if (!isMotorForegroundAllowed()) return
        repeat(2) { reader.clickBack() }
        handler.postDelayed({
            if (!pausedAfterAccept && isMotorForegroundAllowed()) runGrabberTick()
        }, 400)
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

    private fun FlexBlockOffer.toLogEntry(
        status: OfferStatus,
        reason: String,
        stationOverride: String = stationText,
    ): OfferLogEntry {
        val pay = payAmount ?: FlexGrabberEvaluator.parsePay(payText) ?: 0.0
        val hours = durationHours ?: 0.0
        val hourly = hourlyRate ?: if (hours > 0.1) pay / hours else 0.0
        return OfferLogEntry(
            price = pay,
            hourlyRate = hourly,
            durationHours = hours,
            timeWindow = timeText,
            station = stationOverride.ifBlank { stationText },
            status = status,
            reason = reason,
        )
    }
}
