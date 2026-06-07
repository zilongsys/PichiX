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
import com.oceanlab.pichix.data.BotEventLog
import com.oceanlab.pichix.data.PichiFileLog
import com.oceanlab.pichix.data.FlexState
import com.oceanlab.pichix.data.FlexReturnTriggersEvaluator
import com.oceanlab.pichix.data.FlexReturnTriggersStore
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

        /** Pausa manual del motor (clics, scroll, Return 2) para navegar Flex sin interferencias. */
        @Volatile var motorPausedForNavigation = false

        const val MOTOR_PAUSE_CHANGED = "com.oceanlab.pichix.MOTOR_PAUSE_CHANGED"

        @Volatile private var instance: PichixAccessibilityService? = null

        fun notifyBotDisabled() {
            instance?.stopEngine()
        }

        fun resumeFromPause(context: Context) {
            pausedAfterAccept = false
            syncEngine(context)
            BotEventLog.log(context, BotEventLog.CAT_PAUSE, "Bot reanudado")
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(BOT_RESUMED))
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(BOT_STATE_CHANGED))
            PichixForegroundService.refreshNotification(context)
        }

        fun syncEngine(context: Context) {
            instance?.scheduleWork()
        }

        fun scrollDownManual() = instance?.performScrollDown()
        fun scrollUpManual() = instance?.performScrollUp()
        fun returnToOffers() = instance?.performReturnToOffers(manual = true)

        fun toggleMotorPauseForNavigation(context: Context) {
            setMotorPausedForNavigation(!motorPausedForNavigation, context)
        }

        fun setMotorPausedForNavigation(paused: Boolean, context: Context) {
            motorPausedForNavigation = paused
            instance?.onMotorPauseChanged()
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(MOTOR_PAUSE_CHANGED))
            PichixForegroundService.refreshNotification(context)
        }
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
    private var returnInFlight = false
    /** Tras completar Return 2, ignorar nuevos disparos hasta que la UI se estabilice. */
    private var returnSettleUntilMs = 0L
    private var returnStepOffersRunnable: Runnable? = null
    private val returnStepFinishRunnable = Runnable { completeReturnSequence() }
    private var burstActive = false
    private var nextBurstAtMs = 0L
    private var burstEndsAtMs = 0L
    private var lastScreenKind: String? = null
    private var lastLoggedBotRunning: Boolean? = null

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
        if (!settings.isBotEnabled || pausedAfterAccept || motorPausedForNavigation) return
        if (pkg != target) return
        if (!isMotorForegroundAllowed()) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
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
        returnInFlight = false
        returnSettleUntilMs = 0L
        cancelReturnDelayedSteps()
        burstActive = false
        nextBurstAtMs = 0L
        burstEndsAtMs = 0L
        FlexState.resetScrollCycle()
        scrollSigsBefore = ""
        scrollingDown = true
        lastScreenKind = null
        logBotRunningState(force = true)
        BotEventLog.cancelOpenBurst(this)
    }

    private fun updateBurstState() {
        if (!settings.flexBurstClickEnabled) {
            if (burstActive) {
                BotEventLog.onBurstEnd(this, "Ráfaga desactivada en ajustes")
            } else {
                BotEventLog.cancelOpenBurst(this)
            }
            burstActive = false
            nextBurstAtMs = 0L
            burstEndsAtMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (nextBurstAtMs == 0L) {
            nextBurstAtMs = now + settings.nextBurstIntervalMs()
        }
        if (!burstActive && now >= nextBurstAtMs) {
            burstActive = true
            val burstDurationMs = settings.nextBurstDurationMs()
            burstEndsAtMs = now + burstDurationMs
            val burstSec = (burstDurationMs / 1000L).coerceAtLeast(1)
            val msg = "Ráfaga iniciada (~${burstSec}s, cada ${settings.flexBurstClickIntervalMs}ms)"
            BotEventLog.onBurstStart(this, msg)
            postObserver(msg)
        } else if (burstActive && now >= burstEndsAtMs) {
            burstActive = false
            nextBurstAtMs = now + settings.nextBurstIntervalMs()
            val mins = ((nextBurstAtMs - now) / 60_000L).coerceAtLeast(1)
            val msg = "Ráfaga finalizada — próxima en ~$mins min"
            BotEventLog.onBurstEnd(this, msg)
            postObserver(msg)
        }
    }

    /**
     * Evalúa ofertas visibles y acepta si cumplen criterio.
     * @param scrollIfEmpty si false (ráfaga), no hace scroll cuando la lista está vacía.
     * @return true si se inició flujo de aceptación.
     */
    private fun processVisibleOffers(
        text: String,
        scrollIfEmpty: Boolean,
        burstMode: Boolean,
    ): Boolean {
        val flags = reader.detectScreenFlags(text, settings)
        if (flags.captcha) return false

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
            if (!burstMode) {
                logGrabEvalThrottled("Lista sin ofertas parseadas (revisa ids offer_pay)")
            }
            if (scrollIfEmpty) maybeScrollList()
            return false
        }

        logger.logVisibleOffers(offers)

        val evaluated = offers.map { offer ->
            FlexOfferSelector.EvaluatedOffer(
                offer,
                FlexGrabberEvaluator.evaluateListRow(offer, settings, text),
            )
        }
        val winner = FlexOfferSelector.pickAcceptable(evaluated, settings)
        if (winner != null) {
            val prefix = if (burstMode) "Ráfaga → " else ""
            postObserver(prefix + FlexOfferSelector.selectionSummary(winner, evaluated, settings))
            handleAccept(winner)
            return true
        }

        for ((offer, result) in evaluated) {
            when (result) {
                FlexGrabResult.REJECT -> {
                    logGrabEvalThrottled(
                        (if (burstMode) "Ráfaga: " else "") +
                            "No cumple regla: ${offer.stationText} ${offer.payText} " +
                            "(${offer.hourlyRate?.let { "%.1f".format(it) } ?: "?"} \$/h)",
                    )
                    if (settings.flexCancelBadBlocks) {
                        logger.log(offer.toLogEntry(OfferStatus.REJECTED, "Criterio grabber"))
                    }
                }
                FlexGrabResult.SKIP -> {
                    logGrabEvalThrottled(
                        (if (burstMode) "Ráfaga: " else "") +
                            "Datos incompletos: ${offer.stationText} ${offer.payText}",
                    )
                }
                else -> Unit
            }
        }

        if (scrollIfEmpty) maybeScrollList()
        return false
    }

    private fun tryRefreshClick(screenText: String, burstMode: Boolean) {
        if (!settings.flexClickRefreshEnabled) return
        val screenOk = reader.screenMatchesForClick(
            settings.flexClickScreenText,
            screenText,
            settings.flexClickScreenMatchMode,
            settings.flexClickScreenIgnoreCase,
        )
        if (!screenOk && settings.flexClickScreenText.isNotBlank()) {
            if (!burstMode) {
                logGrabEvalThrottled(
                    "Clic omitido: pantalla no coincide con «${settings.flexClickScreenText}» " +
                        "(lista=${reader.isOnOffersListScreen(screenText)})",
                )
            }
            return
        }
        if (!screenOk && settings.flexClickScreenText.isBlank()) return
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
            if (!burstMode) postObserver("Clic: no se encontró «${settings.flexRefreshButtonText}»")
        } else if (burstMode && settings.debugLogEnabled) {
            postObserver("Ráfaga → Refresh")
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

    private fun scheduleWork() {
        handler.removeCallbacks(grabLoopRunnable)
        handler.removeCallbacks(timerRunnable)
        if (!settings.isBotEnabled) return
        logBotRunningState()
        handler.postDelayed(grabLoopRunnable, nextGrabberDelayMs())
        handler.postDelayed(timerRunnable, FlexState.flexTimerSec * 1000L)
    }

    private fun logBotRunningState(force: Boolean = false) {
        val running = settings.isBotEnabled && !pausedAfterAccept && !motorPausedForNavigation
        if (!force && lastLoggedBotRunning == running) return
        lastLoggedBotRunning = running
        when {
            !settings.isBotEnabled -> BotEventLog.log(this, BotEventLog.CAT_BOT, "Bot desactivado")
            pausedAfterAccept -> BotEventLog.log(this, BotEventLog.CAT_PAUSE, "Bot pausado")
            motorPausedForNavigation -> BotEventLog.log(this, BotEventLog.CAT_PAUSE, "Motor pausado (navegación)")
            else -> BotEventLog.log(this, BotEventLog.CAT_BOT, "Bot activo")
        }
    }

    private fun trackScreenChange(text: String, flags: FlexScreenReader.ScreenFlags) {
        val kind = when {
            flags.captcha -> "bloqueo"
            flags.onOffersList -> "lista ofertas"
            reader.isOnOfferDetailScreen() -> "detalle bloque"
            flags.shouldReturnToOffers -> "fuera de ofertas"
            flags.onFlexHomeTabs -> "inicio Flex"
            else -> "otra"
        }
        if (kind == lastScreenKind) return
        lastScreenKind = kind
        BotEventLog.log(this, BotEventLog.CAT_SCREEN, "Pantalla → $kind")
    }

    private fun nextGrabberDelayMs(): Long {
        settings = AppSettings(this)
        if (burstActive && settings.flexBurstClickEnabled) {
            return settings.flexBurstClickIntervalMs
        }
        return settings.nextGrabDelayMs()
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
        if (motorPausedForNavigation) return
        if (!isMotorForegroundAllowed()) return
        val text = reader.readFullScreenText()
        val flags = reader.detectScreenFlags(text, settings)
        trackScreenChange(text, flags)

        when {
            flags.captcha -> postObserver("Captcha detectado")
            flags.blockUnavailable -> postObserver("Bloque no disponible en pantalla")
            flags.offerScheduled -> postObserver("Oferta programada en pantalla")
        }

        if (flags.captcha && settings.autoPauseOnCaptcha) {
            pausedAfterAccept = true
            postBotPaused()
        }

        PauseByOverClicksController.onScreenText(this, reader.readFlexOverlayText())

        maybeAutoReturnToOffers(text, flags)
    }

    private var lastReturnProbeLogMs = 0L

    private fun returnSettleMs(): Long =
        maxOf(settings.flexReturnDetectCooldownSec.coerceAtLeast(1) * 1000L, 5_000L)

    private fun maybeAutoReturnToOffers(text: String, flags: FlexScreenReader.ScreenFlags? = null) {
        if (returnInFlight || motorPausedForNavigation || !settings.flexAutoReturnToOffers) return
        val now = System.currentTimeMillis()
        if (now < returnSettleUntilMs) return
        if (!isMotorForegroundAllowed()) return
        val screen = text.ifBlank { reader.readFullScreenText() }
        val f = flags ?: reader.detectScreenFlags(screen, settings)
        if (f.captcha) return

        if (reader.isOnOfferFlowScreen(screen)) return

        val triggers = FlexReturnTriggersStore.load(settings)
        val matched = FlexReturnTriggersEvaluator.firstMatch(screen, triggers) ?: return

        if (settings.debugLogEnabled && now - lastReturnProbeLogMs > 12_000L) {
            lastReturnProbeLogMs = now
            val snippet = screen.replace('\n', ' ').take(120)
            Log.d(
                TAG,
                "Return probe: offerFlow=false listIds=${reader.hasOfferListMarkers()} " +
                    "detailIds=${reader.hasOfferDetailMarkers()} match=${matched.displayTitle()} «$snippet»",
            )
        }

        if (!f.shouldReturnToOffers) return

        val cooldownMs = settings.flexReturnDetectCooldownSec.coerceAtLeast(1) * 1000L
        if (now - lastReturnMs <= cooldownMs) return

        lastReturnMs = now
        postObserver("Return detectado: ${matched.displayTitle()}")
        performReturnToOffers()
    }

    private fun runGrabberTick() {
        settings = AppSettings(this)
        if (!settings.isBotEnabled || pausedAfterAccept || motorPausedForNavigation || returnInFlight) {
            scheduleWork()
            return
        }
        updateBurstState()
        if (!isMotorForegroundAllowed()) {
            scheduleWork()
            return
        }
        scheduleWork()
        val text = reader.readFullScreenText()
        trackScreenChange(text, reader.detectScreenFlags(text, settings))
        maybeAutoReturnToOffers(text)
        if (returnInFlight) return
        grabInFlight = true
        try {
            if (burstActive && settings.flexBurstClickEnabled) {
                tryRefreshClick(text, burstMode = true)
                processVisibleOffers(text, scrollIfEmpty = false, burstMode = true)
                return
            }
            if (settings.flexClickRefreshEnabled) {
                tryRefreshClick(text, burstMode = false)
            }

            warnIfTariffRulesMisconfigured()
            processVisibleOffers(text, scrollIfEmpty = true, burstMode = false)
        } finally {
            grabInFlight = false
        }
    }

    private fun handleAccept(offer: FlexBlockOffer) {
        val simulation = settings.dryRunMode
        val shouldSchedule = settings.flexAutoAccept && !simulation
        BotEventLog.log(
            this,
            BotEventLog.CAT_OFFER,
            "Intento: ${offer.stationText} ${offer.payText} " +
                "(${offer.hourlyRate?.let { "%.1f".format(it) } ?: "?"} \$/h)",
        )

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
        BotEventLog.log(this, BotEventLog.CAT_PAUSE, "Pausado tras flujo de oferta")
        broadcastState()
    }

    private fun maybeScrollList() {
        if (!settings.flexAutoScrollEnabled || scrollInFlight) return
        if (!isMotorForegroundAllowed()) return
        val screen = reader.readFullScreenText()
        if (!reader.detectScreenFlags(screen, settings).onOffersList) return
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
        postObserver("Scroll ${if (down) "↓" else "↑"} (#${FlexState.counterScroll})")
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

    private fun onMotorPauseChanged() {
        if (motorPausedForNavigation) {
            cancelPendingMotorActions()
            grabInFlight = false
        } else if (settings.isBotEnabled && !pausedAfterAccept) {
            scheduleWork()
        }
    }

    private fun cancelReturnDelayedSteps() {
        returnStepOffersRunnable?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(returnStepFinishRunnable)
        returnStepOffersRunnable = null
    }

    private fun performReturnToOffers(manual: Boolean = false) {
        if (returnInFlight) return
        if (!manual && motorPausedForNavigation) return
        if (!isMotorForegroundAllowed()) {
            if (manual) postObserver("Test Return → Flex no en primer plano")
            return
        }
        if (reader.isOnOfferFlowScreen()) {
            if (manual) postObserver("Test Return → ya en ofertas o detalle de bloque")
            return
        }

        cancelReturnDelayedSteps()
        returnInFlight = true
        cancelPendingMotorActions()
        grabInFlight = false
        scrollInFlight = false
        postObserver(
            if (manual) "Test Return → iniciando (clics pausados)" else "Return → fuera de ofertas (clics pausados)",
        )

        if (!reader.clickFlexDrawerMenu()) {
            repeat(2) { reader.clickBack() }
            postObserver("Return → menú no encontrado, 2× Atrás")
            handler.postDelayed(returnStepFinishRunnable, settings.nextReturnStepDelayMs())
            return
        }
        postObserver("Return → menú ≡ abierto")
        val afterMenuMs = settings.nextReturnStepDelayMs()
        returnStepOffersRunnable = Runnable {
            if (!returnInFlight) return@Runnable
            if (!isMotorForegroundAllowed()) {
                completeReturnSequence()
                return@Runnable
            }
            val offersClicked = reader.clickTextButton("Offers", partial = false) ||
                reader.clickTextButton("Offers", partial = true)
            postObserver(
                if (offersClicked) "Return → Offers" else "Return → no se encontró Offers en menú",
            )
            handler.postDelayed(returnStepFinishRunnable, settings.nextReturnStepDelayMs())
        }
        handler.postDelayed(returnStepOffersRunnable!!, afterMenuMs)
    }

    private fun completeReturnSequence() {
        cancelReturnDelayedSteps()
        returnInFlight = false
        returnSettleUntilMs = System.currentTimeMillis() + returnSettleMs()
        grabInFlight = false
        scrollInFlight = false
        FlexState.resetScrollCycle()
        scrollSigsBefore = ""
        scrollingDown = true

        when {
            reader.isOnOffersListScreen() -> postObserver("Return → lista de ofertas OK, motor reanudado")
            reader.isOnOfferDetailScreen() -> postObserver("Return → detalle de bloque (sin re-retorno)")
            else -> postObserver("Return → secuencia terminada")
        }

        if (!pausedAfterAccept && !motorPausedForNavigation && settings.isBotEnabled && isMotorForegroundAllowed()) {
            scheduleWork()
        }
    }

    private fun postObserver(message: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(OBSERVER_EVENT).putExtra("message", message),
        )
        Log.d(TAG, "Observer: $message")
        routeObserverToBotLog(message)
    }

    private fun routeObserverToBotLog(message: String) {
        if (message.contains("Ráfaga iniciada", ignoreCase = true) ||
            message.contains("Ráfaga finalizada", ignoreCase = true)
        ) {
            return
        }
        val category = when {
            message.contains("Ráfaga", ignoreCase = true) -> BotEventLog.CAT_BURST
            message.contains("Return", ignoreCase = true) -> BotEventLog.CAT_RETURN
            message.contains("Scroll", ignoreCase = true) ||
                message.contains("Fin de lista", ignoreCase = true) -> BotEventLog.CAT_SCROLL
            message.contains("Clic", ignoreCase = true) ||
                message.contains("Refresh", ignoreCase = true) -> BotEventLog.CAT_CLICK
            message.contains("ACEPTADA", ignoreCase = true) ||
                message.contains("SIMULACIÓN", ignoreCase = true) ||
                message.contains("Detalle:", ignoreCase = true) ||
                message.contains("Rechazada", ignoreCase = true) ||
                message.contains("Intento:", ignoreCase = true) -> BotEventLog.CAT_OFFER
            message.contains("Captcha", ignoreCase = true) ||
                message.contains("bloqueo", ignoreCase = true) ||
                message.contains("Pantalla", ignoreCase = true) -> BotEventLog.CAT_SCREEN
            else -> BotEventLog.CAT_BOT
        }
        BotEventLog.log(this, category, message)
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
