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
import com.oceanlab.pichix.analyzer.FlexTakeOutcomeReader
import com.oceanlab.pichix.analyzer.OfferListDetailMatcher
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
import com.oceanlab.pichix.data.FlexMessageHub
import com.oceanlab.pichix.util.FlexAlertDispatcher
import com.oceanlab.pichix.util.AlertManager
import com.oceanlab.pichix.util.BlockDateFormatter
import com.oceanlab.pichix.util.CallOnBlockHelper
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
        /** Reintentos inmediatos (sin espera fija) hasta que Offer Details esté listo. */
        private const val DETAIL_ACCEPT_MAX_ATTEMPTS = 50
        /** Reintentos tras Refresh hasta releer lista actualizada. */
        private const val POST_REFRESH_MAX_ATTEMPTS = 40
        /** Reintentos tras Schedule hasta leer mensaje de Flex (scheduled / unavailable). */
        private const val SCHEDULE_OUTCOME_MAX_ATTEMPTS = 50
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
    private var detailAcceptRunnable: Runnable? = null
    private var postRefreshRunnable: Runnable? = null
    private var scheduleOutcomeRunnable: Runnable? = null

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

        val evaluated = offers.map { offer ->
            val eval = FlexGrabberEvaluator.evaluateListRowDetailed(offer, settings, text)
            FlexOfferSelector.EvaluatedOffer(offer, eval.result, eval.reason)
        }
        val winner = FlexOfferSelector.pickAcceptable(evaluated, settings)
        if (winner != null) {
            val prefix = if (burstMode) "Ráfaga → " else ""
            postObserver(prefix + FlexOfferSelector.selectionSummary(winner, evaluated, settings))
            val listReason = evaluated.firstOrNull {
                it.offer.index == winner.index &&
                    (it.result == FlexGrabResult.ACCEPT || it.result == FlexGrabResult.SIMULATED_ACCEPT)
            }?.reason?.ifBlank { null } ?: "Cumple criterios en lista"
            handleAccept(winner, listReason)
            return true
        }

        for (item in evaluated) {
            when (item.result) {
                FlexGrabResult.REJECT -> {
                    val detail = item.reason.ifBlank { "No cumple criterios" }
                    logGrabEvalThrottled(
                        (if (burstMode) "Ráfaga: " else "") +
                            "No toma: ${item.offer.stationText} ${item.offer.payText} — $detail",
                    )
                    logger.log(item.offer.toLogEntry(OfferStatus.SEEN, detail))
                }
                FlexGrabResult.SKIP -> {
                    val detail = item.reason.ifBlank { "Datos incompletos" }
                    logGrabEvalThrottled(
                        (if (burstMode) "Ráfaga: " else "") +
                            "Omitida: ${item.offer.stationText} ${item.offer.payText} — $detail",
                    )
                    logger.logSeenIfNew(item.offer)
                }
                else -> logger.logSeenIfNew(item.offer)
            }
        }

        if (scrollIfEmpty) maybeScrollList()
        return false
    }

    private fun tryRefreshClick(screenText: String, burstMode: Boolean): Boolean {
        if (!burstMode && !settings.flexClickRefreshEnabled) return false
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
            return false
        }
        if (!screenOk && settings.flexClickScreenText.isBlank()) return false
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
        return clicked
    }

    /** Tras Refresh: relee pantalla y evalúa sin esperar al siguiente ciclo del grabber. */
    private fun beginPostRefreshOfferAnalysis(listSignatureBeforeRefresh: String) {
        cancelPostRefreshAnalysis()
        var attempt = 0
        val runnable = object : Runnable {
            override fun run() {
                if (postRefreshRunnable !== this) return
                if (!isMotorForegroundAllowed()) {
                    cancelPostRefreshAnalysis()
                    return
                }
                attempt++
                val freshText = reader.readFullScreenText()
                val sigNow = reader.offerListSignature()
                val ready = when {
                    attempt >= POST_REFRESH_MAX_ATTEMPTS -> true
                    attempt < 2 -> false
                    sigNow != listSignatureBeforeRefresh -> true
                    attempt >= 6 -> true
                    else -> false
                }
                if (!ready && attempt < POST_REFRESH_MAX_ATTEMPTS) {
                    handler.post(this)
                    return
                }
                cancelPostRefreshAnalysis()
                processVisibleOffers(freshText, scrollIfEmpty = true, burstMode = false)
            }
        }
        postRefreshRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelPostRefreshAnalysis() {
        postRefreshRunnable?.let { handler.removeCallbacks(it) }
        postRefreshRunnable = null
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
        if (!::reader.isInitialized) return
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

        val overlayText = reader.readFlexOverlayText()
        maybeStopBurstForFlexBanner(overlayText)
        PauseByOverClicksController.onScreenText(this, overlayText)
        if (overlayText.isNotBlank()) {
            FlexAlertDispatcher.onFlexText(
                context = this,
                settings = settings,
                text = overlayText,
                source = FlexMessageHub.Source.IN_APP,
            )
        }

        maybeAutoReturnToOffers(text, flags)
    }

    /** Detiene la ráfaga si Flex muestra el banner in-app de demasiados toques. */
    private fun maybeStopBurstForFlexBanner(overlayText: String) {
        if (!burstActive) return
        if (!FlexBlockingPhrases.isFlexThrottleBanner(overlayText)) return
        burstActive = false
        nextBurstAtMs = System.currentTimeMillis() + settings.nextBurstIntervalMs()
        val msg = "Ráfaga detenida — banner Flex (demasiados clics)"
        BotEventLog.onBurstEnd(this, msg)
        postObserver(msg)
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
        if (!::reader.isInitialized) {
            scheduleWork()
            return
        }
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
        val overlayText = reader.readFlexOverlayText()
        maybeStopBurstForFlexBanner(overlayText)
        if (PauseByOverClicksController.onScreenText(this, overlayText)) {
            return
        }
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
            val sigBeforeRefresh = reader.offerListSignature()
            val refreshed = if (settings.flexClickRefreshEnabled) {
                tryRefreshClick(text, burstMode = false)
            } else {
                false
            }

            warnIfTariffRulesMisconfigured()
            if (refreshed && settings.flexReanalyzeAfterRefreshEnabled) {
                beginPostRefreshOfferAnalysis(sigBeforeRefresh)
            } else {
                processVisibleOffers(text, scrollIfEmpty = true, burstMode = false)
            }
        } finally {
            grabInFlight = false
        }
    }

    private fun handleAccept(offer: FlexBlockOffer, listReason: String) {
        val simulation = settings.dryRunMode
        val shouldSchedule = settings.flexAutoAccept && !simulation
        val actionStartedAt = System.currentTimeMillis()
        BotEventLog.log(
            this,
            BotEventLog.CAT_OFFER,
            "Intento: ${offer.stationText} ${offer.payText} " +
                "(${offer.hourlyRate?.let { "%.1f".format(it) } ?: "?"} \$/h) — $listReason",
        )

        if (!reader.clickOfferCardAtIndex(offer.index)) {
            postObserver("No se pudo abrir la oferta en la lista")
            logger.log(
                offer.toLogEntry(
                    OfferStatus.MISS,
                    "No se pudo abrir tarjeta en lista (índice ${offer.index})",
                    actionStartedAt = actionStartedAt,
                ),
            )
            finishBlockTakeFlow(pauseBot = shouldPauseAfterMiss())
            return
        }

        if (settings.offerClickSoundEnabled) {
            AlertManager(this).playFlexNotificationAlert(
                settings.offerClickSoundUri,
                settings.offerClickSoundRepeatCount,
            )
        }

        beginDetailAcceptFlow(offer, listReason, shouldSchedule, simulation, actionStartedAt)
    }

    /**
     * Tras abrir la tarjeta: valida y Schedule sin espera artificial.
     * Si el detalle aún no cargó, reintenta al instante (siguiente ciclo del handler).
     */
    private fun beginDetailAcceptFlow(
        offer: FlexBlockOffer,
        listReason: String,
        shouldSchedule: Boolean,
        simulation: Boolean,
        actionStartedAt: Long,
    ) {
        cancelDetailAcceptRetries()
        var attempt = 0
        val runnable = object : Runnable {
            override fun run() {
                if (detailAcceptRunnable !== this) return
                if (!isMotorForegroundAllowed()) {
                    logger.log(
                        offer.toLogEntry(
                            OfferStatus.MISS,
                            "Flex salió de primer plano tras abrir detalle",
                            actionStartedAt = actionStartedAt,
                        ),
                    )
                    finishBlockTakeFlow(pauseBot = shouldPauseAfterMiss())
                    return
                }
                attempt++
                if (!reader.isDetailReadyForAccept(needsSchedule = shouldSchedule)) {
                    val screenText = reader.readFullScreenText()
                    if (reader.isOnOfferDetailScreen() &&
                        OfferListDetailMatcher.isBlockUnavailable(screenText)
                    ) {
                        cancelDetailAcceptRetries()
                        val details = reader.readBlockDetails()
                        val station = offer.stationText.ifBlank { details["station"].orEmpty() }
                        val blockDateShort = BlockDateFormatter.formatShort(details["date"].orEmpty())
                        handleTakeMiss(
                            offer,
                            "Bloque ya no disponible (desapareció o cambió tras el clic)",
                            station,
                            blockDateShort,
                            actionStartedAt,
                        )
                        return
                    }
                    if (attempt >= DETAIL_ACCEPT_MAX_ATTEMPTS) {
                        cancelDetailAcceptRetries()
                        logger.log(
                            offer.toLogEntry(
                                OfferStatus.MISS,
                                "Offer Details no cargó ($attempt intentos)",
                                actionStartedAt = actionStartedAt,
                                actionCompletedAt = System.currentTimeMillis(),
                            ),
                        )
                        postObserver("Detalle no cargó a tiempo — ${offer.stationText}")
                        finishBlockTakeFlow(pauseBot = shouldPauseAfterMiss())
                        return
                    }
                    handler.post(this)
                    return
                }
                cancelDetailAcceptRetries()
                processOfferDetailReady(
                    offer, listReason, shouldSchedule, simulation, actionStartedAt,
                )
            }
        }
        detailAcceptRunnable = runnable
        handler.post(runnable)
    }

    private fun processOfferDetailReady(
        offer: FlexBlockOffer,
        listReason: String,
        shouldSchedule: Boolean,
        simulation: Boolean,
        actionStartedAt: Long,
    ) {
        val details = reader.readBlockDetails()
        val station = offer.stationText.ifBlank { details["station"].orEmpty() }
        val blockDateShort = BlockDateFormatter.formatShort(details["date"].orEmpty())
        val screenText = reader.readFullScreenText()

        if (OfferListDetailMatcher.isBlockUnavailable(screenText)) {
            handleTakeMiss(
                offer,
                "Bloque ya no disponible (desapareció o cambió tras el clic)",
                station,
                blockDateShort,
                actionStartedAt,
            )
            return
        }

        val allMismatches = OfferListDetailMatcher.verify(offer, details)
        val softOnly = allMismatches.isNotEmpty() &&
            !OfferListDetailMatcher.shouldTriggerMismatchAction(offer, details)
        if (softOnly) {
            val warn = OfferListDetailMatcher.formatReason(allMismatches) +
                " · aviso menor, se continúa"
            BotEventLog.log(this, BotEventLog.CAT_OFFER, warn)
        }

        if (OfferListDetailMatcher.shouldTriggerMismatchAction(offer, details)) {
            val reason = OfferListDetailMatcher.formatReason(allMismatches)
            handleDetailMismatch(
                offer, reason, station, blockDateShort, listReason, actionStartedAt,
            )
            return
        }

        if (!shouldSchedule) {
            val note = if (simulation) {
                "Simulación · $listReason"
            } else {
                "Detalle abierto · $listReason · sin Schedule automático"
            }
            logger.log(
                offer.toLogEntry(
                    if (simulation) OfferStatus.SIMULATED else OfferStatus.SIMULATED,
                    note,
                    stationOverride = station,
                    blockDate = blockDateShort,
                    actionStartedAt = actionStartedAt,
                    actionCompletedAt = System.currentTimeMillis(),
                ),
            )
            postObserver(
                if (simulation) {
                    "SIMULACIÓN: ${offer.stationText} ${offer.payText} (Offer Details, sin Schedule)"
                } else {
                    "Detalle: ${offer.stationText} — revisa y pulsa Schedule manualmente"
                },
            )
            finishBlockTakeFlow(pauseBot = settings.autoPauseAfterAccept)
            return
        }

        val detailEval = FlexGrabberEvaluator.evaluateDetailWithListFallback(
            offer = offer,
            details = details,
            settings = settings,
            station = station,
            screenText = screenText,
        )
        if (!detailEval.accepted) {
            val detailCtx = detailContextSummary(details)
            val reason = buildString {
                append(detailEval.reason.ifBlank { "Detalle no cumple criterios" })
                if (detailCtx.isNotBlank()) append(" · Leído: ").append(detailCtx)
                append(" · Lista: ").append(listReason)
            }
            logger.log(
                offer.toLogEntry(
                    OfferStatus.SEEN,
                    reason,
                    stationOverride = station,
                    blockDate = blockDateShort,
                    actionStartedAt = actionStartedAt,
                    actionCompletedAt = System.currentTimeMillis(),
                ),
            )
            postObserver("No toma en detalle: $station — $reason")
            finishBlockTakeFlow(pauseBot = settings.autoPauseAfterAccept)
            return
        }

        if (!reader.clickScheduleOnDetail()) {
            handleTakeMiss(
                offer,
                "No se encontró botón Schedule",
                station,
                blockDateShort,
                actionStartedAt,
            )
            return
        }

        BotEventLog.log(
            this,
            BotEventLog.CAT_OFFER,
            "Schedule pulsado — esperando confirmación Flex: $station",
        )
        beginScheduleOutcomeFlow(
            offer = offer,
            station = station,
            blockDateShort = blockDateShort,
            listReason = listReason,
            detailReason = detailEval.reason,
            actionStartedAt = actionStartedAt,
        )
    }

    /** Tras Schedule: Aceptada solo si Flex muestra scheduled; Miss si block unavailable. */
    private fun beginScheduleOutcomeFlow(
        offer: FlexBlockOffer,
        station: String,
        blockDateShort: String,
        listReason: String,
        detailReason: String,
        actionStartedAt: Long,
    ) {
        cancelScheduleOutcomeFlow()
        var attempt = 0
        val runnable = object : Runnable {
            override fun run() {
                if (scheduleOutcomeRunnable !== this) return
                if (!isMotorForegroundAllowed()) {
                    handleTakeMiss(
                        offer,
                        "Flex salió de primer plano tras Schedule",
                        station,
                        blockDateShort,
                        actionStartedAt,
                    )
                    return
                }
                attempt++
                val screenText = reader.readFullScreenText()
                val overlayText = reader.readFlexOverlayText()
                val reading = FlexTakeOutcomeReader.readWithRecentNotification(
                    screenText = screenText,
                    overlayText = overlayText,
                )
                when (reading.result) {
                    FlexTakeOutcomeReader.Result.SCHEDULED -> {
                        cancelScheduleOutcomeFlow()
                        logScheduleOutcome(
                            offer,
                            accepted = true,
                            flexMessage = reading.flexMessage,
                            station,
                            blockDateShort,
                            listReason,
                            detailReason,
                            actionStartedAt,
                        )
                        finishBlockTakeFlow(pauseBot = settings.autoPauseAfterAccept)
                    }
                    FlexTakeOutcomeReader.Result.BLOCK_UNAVAILABLE -> {
                        cancelScheduleOutcomeFlow()
                        handleTakeMiss(
                            offer,
                            reading.flexMessage.ifBlank { "Block unavailable" },
                            station,
                            blockDateShort,
                            actionStartedAt,
                        )
                    }
                    FlexTakeOutcomeReader.Result.PENDING -> {
                        if (attempt >= SCHEDULE_OUTCOME_MAX_ATTEMPTS) {
                            cancelScheduleOutcomeFlow()
                            handleTakeMiss(
                                offer,
                                "Sin confirmación de Flex tras Schedule ($attempt intentos)",
                                station,
                                blockDateShort,
                                actionStartedAt,
                            )
                        } else {
                            handler.post(this)
                        }
                    }
                }
            }
        }
        scheduleOutcomeRunnable = runnable
        handler.post(runnable)
    }

    private fun logScheduleOutcome(
        offer: FlexBlockOffer,
        accepted: Boolean,
        flexMessage: String,
        station: String,
        blockDateShort: String,
        listReason: String,
        detailReason: String,
        actionStartedAt: Long,
    ) {
        val completedAt = System.currentTimeMillis()
        val reason = buildString {
            append(flexMessage.ifBlank { if (accepted) "Offer scheduled" else "Block unavailable" })
            append(" · Lista: ")
            append(listReason)
            if (detailReason.isNotBlank()) {
                append(" · Detalle: ")
                append(detailReason)
            }
        }
        logger.log(
            offer.toLogEntry(
                if (accepted) OfferStatus.ACCEPTED else OfferStatus.MISS,
                reason,
                stationOverride = station,
                blockDate = blockDateShort,
                actionStartedAt = actionStartedAt,
                actionCompletedAt = completedAt,
            ),
        )
        postObserver(
            if (accepted) {
                "ACEPTADA: $station — ${flexMessage.ifBlank { "scheduled" }}" +
                    if (blockDateShort.isNotBlank()) " ($blockDateShort)" else ""
            } else {
                "PERDIDA: $station — ${flexMessage.ifBlank { "block unavailable" }}"
            },
        )
        if (accepted && settings.callOnBlockWhenAccepted) {
            CallOnBlockHelper.maybeCall(
                this,
                settings,
                "bloque aceptado: $station",
            )
        }
    }

    private fun cancelScheduleOutcomeFlow() {
        scheduleOutcomeRunnable?.let { handler.removeCallbacks(it) }
        scheduleOutcomeRunnable = null
    }

    private fun cancelDetailAcceptRetries() {
        detailAcceptRunnable?.let { handler.removeCallbacks(it) }
        detailAcceptRunnable = null
    }

    private fun handleTakeMiss(
        offer: FlexBlockOffer,
        reason: String,
        station: String,
        blockDateShort: String,
        actionStartedAt: Long,
    ) {
        logger.log(
            offer.toLogEntry(
                OfferStatus.MISS,
                reason,
                stationOverride = station,
                blockDate = blockDateShort,
                actionStartedAt = actionStartedAt,
                actionCompletedAt = System.currentTimeMillis(),
            ),
        )
        postObserver("Perdida: $station — $reason")
        finishBlockTakeFlow(pauseBot = shouldPauseAfterMiss())
    }

    /**
     * Offer Details: lista ≠ detalle. Según Config:
     * - Sonido + quedarse en pantalla (bot pausado, tú decides Schedule/Cancel).
     * - Cancel automático + confirmar diálogo.
     */
    private fun handleDetailMismatch(
        offer: FlexBlockOffer,
        reason: String,
        station: String,
        blockDateShort: String,
        listReason: String,
        actionStartedAt: Long,
    ) {
        val completedAt = System.currentTimeMillis()
        if (settings.usesDetailMismatchAutoCancel()) {
            val cancelClicked = reader.clickCancelOnDetail()
            if (!cancelClicked) {
                logger.log(
                    offer.toLogEntry(
                        OfferStatus.MISS,
                        "$reason · no se encontró botón Cancel",
                        stationOverride = station,
                        blockDate = blockDateShort,
                        actionStartedAt = actionStartedAt,
                        actionCompletedAt = completedAt,
                    ),
                )
                postObserver("Detalle distinto — no se encontró Cancel")
                finishBlockTakeFlow(pauseBot = shouldPauseAfterMiss())
                return
            }
            handler.postDelayed({
                val confirmed = reader.confirmOfferCancelDialog()
                val logReason = when {
                    confirmed -> "$reason · Cancel + confirmación"
                    else -> "$reason · Cancel (sin confirmar diálogo)"
                }
                logger.log(
                    offer.toLogEntry(
                        if (confirmed) OfferStatus.CANCELLED else OfferStatus.MISS,
                        logReason,
                        stationOverride = station,
                        blockDate = blockDateShort,
                        actionStartedAt = actionStartedAt,
                        actionCompletedAt = System.currentTimeMillis(),
                    ),
                )
                postObserver(
                    if (confirmed) {
                        "Detalle distinto — oferta cancelada en Flex"
                    } else {
                        "Detalle distinto — Cancel pulsado, confirma manualmente"
                    },
                )
                finishBlockTakeFlow(
                    pauseBot = if (confirmed) settings.autoPauseAfterAccept else shouldPauseAfterMiss(),
                )
            }, 550)
            return
        }

        AlertManager(this).playSoundOnce(settings.flexDetailMismatchSoundUri)
        logger.log(
            offer.toLogEntry(
                OfferStatus.SEEN,
                "Aviso sonoro · $reason · quedó en Offer Details (Schedule/Cancel manual) · Lista: $listReason",
                stationOverride = station,
                blockDate = blockDateShort,
                actionStartedAt = actionStartedAt,
                actionCompletedAt = completedAt,
            ),
        )
        postObserver("Detalle distinto — aviso sonoro, revisa Offer Details (Schedule/Cancel)")
        finishBlockTakeFlow(pauseBot = settings.autoPauseAfterAccept)
    }

    private fun detailContextSummary(details: Map<String, String>): String = buildString {
        val pay = details["pay_range"]?.trim().orEmpty()
        val time = details["time_window"]?.trim().orEmpty()
        val st = details["station"]?.trim().orEmpty()
        if (pay.isNotBlank()) append("pago=").append(pay.take(24))
        if (time.isNotBlank()) {
            if (isNotEmpty()) append(", ")
            append("horario=").append(time.take(32))
        }
        if (st.isNotBlank()) {
            if (isNotEmpty()) append(", ")
            append("est=").append(st.take(20))
        }
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

    private fun shouldPauseAfterMiss(): Boolean = !settings.flexContinueOnTakeMiss

    private fun finishBlockTakeFlow(pauseBot: Boolean) {
        cancelDetailAcceptRetries()
        cancelScheduleOutcomeFlow()
        if (pauseBot) {
            pausedAfterAccept = true
            postBotPaused()
            BotEventLog.log(this, BotEventLog.CAT_PAUSE, "Pausado tras flujo de oferta")
        } else {
            BotEventLog.log(this, BotEventLog.CAT_OFFER, "Flujo de oferta terminado — bot sigue activo")
            scheduleWork()
        }
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
        cancelDetailAcceptRetries()
        cancelPostRefreshAnalysis()
        cancelScheduleOutcomeFlow()
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
        blockDate: String = "",
        actionStartedAt: Long = 0L,
        actionCompletedAt: Long = 0L,
    ): OfferLogEntry {
        val pay = payAmount ?: FlexGrabberEvaluator.parsePay(payText) ?: 0.0
        val hours = durationHours ?: 0.0
        val hourly = hourlyRate ?: if (hours > 0.1) pay / hours else 0.0
        return OfferLogEntry(
            price = pay,
            hourlyRate = hourly,
            durationHours = hours,
            timeWindow = timeText,
            blockDate = blockDate,
            station = stationOverride.ifBlank { stationText },
            status = status,
            reason = reason,
            actionStartedAt = actionStartedAt,
            actionCompletedAt = actionCompletedAt,
        )
    }
}
