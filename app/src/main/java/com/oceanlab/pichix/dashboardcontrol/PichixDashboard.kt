package com.oceanlab.pichix.dashboardcontrol

// ============================================================================
// PichixDashboard — lo propio de PichiX para el Centro de control.
//
// Enganches (una línea cada uno, ver docs/dashboardcontrol/CURSOR_INTEGRACION.md):
//   PichixApplication.onCreate  → PichixDashboard.init(this)
//   BotEventLog.log             → PichixDashboard.onBotEvent(category, message)
//   PichiFileLog.enqueue        → PichixDashboard.onFileLog(level, tag, msg)
//   OfferLogger.log             → PichixDashboard.onOffer(tracedEntry)
//
// Pausar/Reanudar hacen lo mismo que la pausa por notificación de la app
// (pausedAfterAccept + broadcasts + notificación), sin apagar el bot.
// ============================================================================

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.BuildConfig
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.BotEventLog
import com.oceanlab.pichix.data.DayStats
import com.oceanlab.pichix.data.OfferLogEntry
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus
import com.oceanlab.pichix.data.PichiFileLog
import com.oceanlab.pichix.service.BotServiceCoordinator
import com.oceanlab.pichix.service.PichixAccessibilityService
import com.oceanlab.pichix.service.PichixForegroundService
import com.oceanlab.pichix.ui.CategoryUiHelper
import com.oceanlab.pichix.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object PichixDashboard : DashboardBridge {

    private const val G_HOME = "Home"
    private const val G_CFG_CONTROL = "Config · Control del bot"
    private const val G_CFG_RITMO = "Config · Ritmo del bot"
    private const val G_CFG_FLEX = "Config · En Flex"
    private const val G_CFG_PANTALLA = "Config · Pantalla y ofertas"
    private const val G_CFG_PAUSAS = "Config · Pausas"
    private const val G_CFG_DIAG = "Config · Diagnóstico"
    private const val G_CFG_PREF = "Config · Preferencias"
    private const val G_TARIFAS = "Tarifas"
    private const val G_ALERTAS = "Alertas"

    private lateinit var app: Context
    private var settingsImpl: DashboardSettings? = null
    @Volatile private var statsCache: DayStats? = null
    @Volatile private var statsAt = 0L

    override val systemName = "PichiX"
    override val appVersion: String get() = BuildConfig.VERSION_NAME
    override val settings: DashboardSettings? get() = settingsImpl

    /** Application.onCreate. Barato: no toca red ni disco en este hilo. */
    fun init(application: Application) {
        app = application.applicationContext
        settingsImpl = DashboardSettings(app, "pichix_settings", buildSpecs()) {
            // Misma recarga que al guardar en la app: motor + FGS + overlay + logs + UI.
            val settings = AppSettings(app)
            BotServiceCoordinator.syncForegroundService(app)
            PichiFileLog.setFileLogEnabled(settings.fileLogEnabled)
            AppSettings.markPendingConfigUiReload()
            val lbm = LocalBroadcastManager.getInstance(app)
            lbm.sendBroadcast(Intent(MainActivity.BOT_STATE_CHANGED))
            lbm.sendBroadcast(Intent(CategoryUiHelper.ACTION_CATEGORY_UI_CHANGED))
            MainActivity.notifyAutoAcceptSettingChanged(app, settings.flexAutoAccept)
            MainActivity.notifyReturn2SettingChanged(app, settings.flexAutoReturnToOffers)
            PichixForegroundService.refreshNotification(app)
        }
        DashboardLink.start(app, this)
    }

    // ------------------------------------------------------------------ enganches
    /** Desde BotEventLog.log: el log del bot (pestaña Log de la app). */
    fun onBotEvent(category: String, message: String) {
        if (!DashboardLink.active) return
        val lv = if (category == BotEventLog.CAT_PAUSE) 'W' else 'I'
        if (DashboardLink.wants(lv)) {
            val tag = if (category.startsWith("__BURST")) "ráfaga" else category.lowercase(Locale.ROOT)
            DashboardLink.log(lv, tag, message)
        }
        when (category) {
            BotEventLog.CAT_PAUSE -> DashboardLink.event("bot", "PAUSA", "warn", message)
            BotEventLog.CAT_BOT -> DashboardLink.event("bot", "BOT", "info", message)
        }
    }

    /** Desde PichiFileLog.enqueue: log técnico (el mismo que va a archivo). */
    fun onFileLog(level: String, tag: String, msg: String) {
        val lv = level.firstOrNull()?.uppercaseChar() ?: 'I'
        if (!DashboardLink.wants(lv)) return
        DashboardLink.log(lv, tag, msg)
    }

    /** Desde OfferLogger.log (después del dedup): cada oferta vista/aceptada/rechazada es un punto en los carriles. */
    fun onOffer(e: OfferLogEntry) {
        if (!DashboardLink.active) return
        val (status, tone) = when (e.status) {
            OfferStatus.ACCEPTED -> "ACEPTADA" to "ok"
            OfferStatus.REJECTED -> "RECHAZADA" to "bad"
            OfferStatus.MISS -> "PERDIDA" to "warn"
            OfferStatus.CANCELLED -> "CANCELADA" to "warn"
            OfferStatus.SIMULATED -> "SIMULADA" to "info"
            OfferStatus.SEEN -> "VISTA" to "muted"
        }
        val title = listOf(e.blockDate, e.timeWindow, money(e.price), e.station).filter { it.isNotBlank() }.joinToString(" · ")
        DashboardLink.event(
            type = "bloque", status = status, tone = tone, title = title,
            data = mapOf(
                "pago" to money(e.price),
                "\$/h" to String.format(Locale.US, "%.2f", e.hourlyRate),
                "horas" to String.format(Locale.US, "%.2f", e.durationHours),
                "estación" to e.station,
                "fecha" to e.blockDate,
                "franja" to e.timeWindow,
                "motivo" to e.reason,
            ),
            notify = e.status == OfferStatus.ACCEPTED,
        )
        statsAt = 0L // que la tarjeta de la PC se actualice
    }

    // ------------------------------------------------------------------ bridge
    override fun extraCommands(): JSONArray = JSONArray()
        .put(JSONObject().put("name", "volver_ofertas").put("label", "Volver a ofertas"))
        .put(JSONObject().put("name", "motor_pausa").put("label", "Pausar/seguir motor (navegación)"))

    override fun pause(): CommandResult = DashboardLink.onMain {
        if (!AppSettings(app).isBotEnabled) return@onMain CommandResult(false, "el bot está apagado en el teléfono")
        if (!PichixAccessibilityService.pausedAfterAccept) {
            PichixAccessibilityService.pausedAfterAccept = true
            val lbm = LocalBroadcastManager.getInstance(app)
            lbm.sendBroadcast(Intent(PichixAccessibilityService.BOT_PAUSED))
            lbm.sendBroadcast(Intent(PichixAccessibilityService.BOT_STATE_CHANGED))
            PichixForegroundService.refreshNotification(app)
            BotEventLog.log(app, BotEventLog.CAT_PAUSE, "Bot pausado desde la PC")
        }
        CommandResult(true)
    }

    override fun resume(): CommandResult = DashboardLink.onMain {
        if (!AppSettings(app).isBotEnabled) return@onMain CommandResult(false, "el bot está apagado en el teléfono: enciéndelo en la app")
        if (PichixAccessibilityService.pausedAfterAccept) PichixAccessibilityService.resumeFromPause(app)
        CommandResult(true)
    }

    override fun runCommand(name: String, args: JSONObject): CommandResult? = when (name) {
        "volver_ofertas" -> DashboardLink.onMain {
            PichixAccessibilityService.returnToOffers()
            CommandResult(true)
        }
        "motor_pausa" -> DashboardLink.onMain {
            PichixAccessibilityService.toggleMotorPauseForNavigation(app)
            CommandResult(true, data = JSONObject().put("motorPausado", PichixAccessibilityService.motorPausedForNavigation))
        }
        else -> null
    }

    override fun state(): JSONObject {
        val s = AppSettings(app)
        val (status, text) = when {
            !s.isBotEnabled -> "idle" to "bot apagado"
            PichixAccessibilityService.pausedAfterAccept -> "paused" to "pausado"
            PichixAccessibilityService.motorPausedForNavigation -> "paused" to "motor en pausa (navegación)"
            s.dryRunMode -> "running" to "buscando bloques · simulación"
            else -> "running" to "buscando bloques"
        }
        val st = todayStats()
        val metrics = JSONArray()
        fun m(k: String, v: String) = metrics.put(JSONObject().put("k", k).put("v", v))
        m("Aceptadas hoy", st?.accepted?.toString() ?: "—")
        m("Ganado hoy", st?.let { money(it.totalEarned) } ?: "—")
        m("Vistas hoy", st?.seen?.toString() ?: "—")
        m("Rechazadas", st?.rejected?.toString() ?: "—")
        m("Perdidas", st?.miss?.toString() ?: "—")
        m("\$/h medio", st?.let { String.format(Locale.US, "%.1f", it.avgHourly) } ?: "—")
        m("Mínimo \$/h", String.format(Locale.US, "%.1f", s.flexMinHourlyRate))
        return JSONObject().put("status", status).put("statusText", text).put("metrics", metrics)
    }

    /** Leer estadísticas cuesta disco: como mucho cada 30 s (y siempre en hilo de fondo). */
    private fun todayStats(): DayStats? {
        val now = System.currentTimeMillis()
        if (now - statsAt > 30_000L) {
            statsCache = try { OfferLogger(app).getTodayStats() } catch (_: Throwable) { statsCache }
            statsAt = now
        }
        return statsCache
    }

    private fun money(v: Double): String =
        if (v % 1.0 == 0.0) "\$${v.toLong()}" else "\$" + String.format(Locale.US, "%.2f", v)

    // ------------------------------------------------------------------ ajustes
    // Grupos = pestañas/secciones de la app (mismo orden visual).
    // Historial / Estadísticas / Log / Simulador / Revisión no exponen ajustes editables.
    private fun s() = AppSettings(app)

    private fun buildSpecs(): List<SettingSpec> = listOf(
        // ── Home ──
        SettingSpec.bool("bot_enabled", "Bot activo", G_HOME,
            help = "Igual que el switch del header en Home",
            get = { s().isBotEnabled }, set = { s().isBotEnabled = it }),
        SettingSpec.bool("overlay_enabled", "Botón flotante", G_HOME,
            get = { s().overlayEnabled }, set = { s().overlayEnabled = it }),
        SettingSpec.bool("dry_run", "Modo simulación (no acepta de verdad)", G_HOME,
            get = { s().dryRunMode }, set = { s().dryRunMode = it }),
        SettingSpec.bool("flex_auto_accept", "Aceptar automáticamente", G_HOME,
            get = { s().flexAutoAccept }, set = { s().flexAutoAccept = it }),
        SettingSpec.bool("flex_auto_return_offers", "Volver a ofertas automáticamente", G_HOME,
            get = { s().flexAutoReturnToOffers }, set = { s().flexAutoReturnToOffers = it }),
        SettingSpec.bool("dark_theme", "Tema oscuro", G_HOME,
            get = { s().useDarkTheme }, set = { s().useDarkTheme = it }),

        // ── Config · Control del bot ──
        SettingSpec.bool("overlay_motor_pause_fab", "FAB pausar motor (navegación)", G_CFG_CONTROL,
            get = { s().overlayMotorPauseFabEnabled }, set = { s().overlayMotorPauseFabEnabled = it }),
        SettingSpec.bool("overlay_test_return_fab", "FAB probar volver a ofertas", G_CFG_CONTROL,
            get = { s().overlayTestReturnEnabled }, set = { s().overlayTestReturnEnabled = it }),

        // ── Config · Ritmo del bot ──
        SettingSpec.choice("flex_click_mode", "Modo de clic", G_CFG_RITMO,
            listOf(AppSettings.CLICK_MODE_BASIC, AppSettings.CLICK_MODE_SMART),
            help = "basic = intervalo fijo; smart = espera aleatoria entre mín y máx",
            get = { s().flexClickMode }, set = { s().flexClickMode = it }),
        SettingSpec.long("flex_grab_interval_ms", "Intervalo (modo basic)", G_CFG_RITMO, 800, 60_000, "ms",
            get = { s().flexGrabIntervalMs }, set = { s().flexGrabIntervalMs = it }),
        SettingSpec.int("flex_smart_click_min_sec", "Espera mínima (smart)", G_CFG_RITMO, 1, 3600, "s",
            get = { s().flexSmartClickMinSec }, set = { s().flexSmartClickMinSec = it }),
        SettingSpec.int("flex_smart_click_max_sec", "Espera máxima (smart)", G_CFG_RITMO, 1, 3600, "s",
            get = { s().flexSmartClickMaxSec }, set = { s().flexSmartClickMaxSec = it }),
        SettingSpec.bool("flex_burst_click_enabled", "Ráfagas de Refresh", G_CFG_RITMO,
            get = { s().flexBurstClickEnabled }, set = { s().flexBurstClickEnabled = it }),
        SettingSpec.int("flex_burst_interval_min_min", "Entre ráfagas (mín)", G_CFG_RITMO, 1, 1440, "min",
            get = { s().flexBurstIntervalMinMin }, set = { s().flexBurstIntervalMinMin = it }),
        SettingSpec.int("flex_burst_interval_max_min", "Entre ráfagas (máx)", G_CFG_RITMO, 1, 1440, "min",
            get = { s().flexBurstIntervalMaxMin }, set = { s().flexBurstIntervalMaxMin = it }),
        SettingSpec.long("flex_burst_click_interval_ms", "Entre clics de la ráfaga", G_CFG_RITMO, 100, 10_000, "ms",
            get = { s().flexBurstClickIntervalMs }, set = { s().flexBurstClickIntervalMs = it }),
        SettingSpec.int("flex_burst_duration_min_sec", "Duración ráfaga (mín)", G_CFG_RITMO, 5, 3600, "s",
            get = { s().flexBurstDurationMinSec }, set = { s().flexBurstDurationMinSec = it }),
        SettingSpec.int("flex_burst_duration_max_sec", "Duración ráfaga (máx)", G_CFG_RITMO, 5, 3600, "s",
            get = { s().flexBurstDurationMaxSec }, set = { s().flexBurstDurationMaxSec = it }),

        // ── Config · En Flex ──
        SettingSpec.bool("flex_only_foreground", "Solo con Flex en primer plano", G_CFG_FLEX,
            get = { s().flexOnlyWhenForeground }, set = { s().flexOnlyWhenForeground = it }),
        SettingSpec.bool("auto_pause_accept", "Pausar tras aceptar", G_CFG_FLEX,
            get = { s().autoPauseAfterAccept }, set = { s().autoPauseAfterAccept = it }),
        SettingSpec.bool("flex_continue_on_take_miss", "Seguir activo tras PERDIDA", G_CFG_FLEX,
            get = { s().flexContinueOnTakeMiss }, set = { s().flexContinueOnTakeMiss = it }),
        SettingSpec.bool("auto_pause_captcha", "Pausar si aparece captcha", G_CFG_FLEX,
            get = { s().autoPauseOnCaptcha }, set = { s().autoPauseOnCaptcha = it }),
        SettingSpec.bool("auto_pause_reserved", "Pausar con notificación de bloque reservado", G_CFG_FLEX,
            get = { s().autoPauseOnReservedNotification }, set = { s().autoPauseOnReservedNotification = it }),
        SettingSpec.int("flex_return_step_min_sec", "Volver a ofertas · paso (mín)", G_CFG_FLEX, 0, 60, "s",
            get = { s().flexReturnStepMinSec }, set = { s().flexReturnStepMinSec = it }),
        SettingSpec.int("flex_return_step_max_sec", "Volver a ofertas · paso (máx)", G_CFG_FLEX, 0, 60, "s",
            get = { s().flexReturnStepMaxSec }, set = { s().flexReturnStepMaxSec = it }),

        // ── Config · Pantalla y ofertas ──
        SettingSpec.bool("flex_click_refresh_enabled", "Pulsar Refresh en cada ciclo", G_CFG_PANTALLA,
            get = { s().flexClickRefreshEnabled }, set = { s().flexClickRefreshEnabled = it }),
        SettingSpec.bool("flex_reanalyze_after_refresh", "Reanalizar tras Refresh", G_CFG_PANTALLA,
            get = { s().flexReanalyzeAfterRefreshEnabled }, set = { s().flexReanalyzeAfterRefreshEnabled = it }),
        SettingSpec.bool("flex_auto_scroll_enabled", "Scroll automático en la lista", G_CFG_PANTALLA,
            get = { s().flexAutoScrollEnabled }, set = { s().flexAutoScrollEnabled = it }),
        SettingSpec.choice("flex_offer_pick_mode", "Qué oferta tomar", G_CFG_PANTALLA,
            listOf(AppSettings.OFFER_PICK_FIRST, AppSettings.OFFER_PICK_BEST),
            help = "first = la primera válida; best = la mejor según el criterio",
            get = { s().flexOfferPickMode }, set = { s().flexOfferPickMode = it }),
        SettingSpec.choice("flex_offer_rank_criterion", "Criterio de «mejor»", G_CFG_PANTALLA,
            listOf(
                AppSettings.OFFER_RANK_HOURLY,
                AppSettings.OFFER_RANK_BLOCK_PAY,
                AppSettings.OFFER_RANK_DURATION_MIN,
                AppSettings.OFFER_RANK_DURATION_MAX,
                AppSettings.OFFER_RANK_START_SOONEST,
            ),
            get = { s().flexOfferRankCriterion }, set = { s().flexOfferRankCriterion = it }),
        SettingSpec.bool("offer_click_sound_enabled", "Sonido al pulsar oferta", G_CFG_PANTALLA,
            get = { s().offerClickSoundEnabled }, set = { s().offerClickSoundEnabled = it }),
        SettingSpec.int("offer_click_sound_repeat", "Repeticiones del sonido de oferta", G_CFG_PANTALLA, 1, 20, null,
            get = { s().offerClickSoundRepeatCount }, set = { s().offerClickSoundRepeatCount = it }),

        // ── Config · Pausas ──
        SettingSpec.bool("pause_by_over_clicks", "Pausa por texto en notificación", G_CFG_PAUSAS,
            get = { s().pauseByOverClicksEnabled }, set = { s().pauseByOverClicksEnabled = it }),
        SettingSpec.text("pause_by_over_clicks_text", "Texto a detectar (pausa)", G_CFG_PAUSAS,
            get = { s().pauseByOverClicksMatchText }, set = { s().pauseByOverClicksMatchText = it }),
        SettingSpec.choice("pause_over_clicks_match_mode", "Modo de coincidencia", G_CFG_PAUSAS,
            listOf(AppSettings.TEXT_MATCH_CONTAINS, AppSettings.TEXT_MATCH_EXACT),
            get = { s().pauseByOverClicksMatchMode }, set = { s().pauseByOverClicksMatchMode = it }),
        SettingSpec.int("pause_by_over_clicks_minutes", "Minutos hasta reanudar", G_CFG_PAUSAS, 1, 24 * 60, "min",
            get = { s().pauseByOverClicksResumeMinutes }, set = { s().pauseByOverClicksResumeMinutes = it }),

        // ── Config · Diagnóstico ──
        SettingSpec.bool("debug_log", "Log de depuración (UI)", G_CFG_DIAG,
            get = { s().debugLogEnabled }, set = { s().debugLogEnabled = it }),
        SettingSpec.bool("file_log", "Log a archivo (bot)", G_CFG_DIAG,
            get = { s().fileLogEnabled }, set = { s().fileLogEnabled = it }),

        // ── Config · Preferencias ──
        SettingSpec.bool("show_category_names", "Mostrar nombres en la barra lateral", G_CFG_PREF,
            get = { s().showCategoryNames }, set = { s().showCategoryNames = it }),

        // ── Tarifas ──
        SettingSpec.choice("flex_tariff_mode", "Modo de tarifas", G_TARIFAS,
            listOf(AppSettings.TARIFF_MODE_CLASSIC, AppSettings.TARIFF_MODE_DETAILED),
            help = "classic = criterios rápidos; detailed = reglas por estación (editar en el teléfono)",
            get = { s().flexTariffMode }, set = { s().flexTariffMode = it }),
        SettingSpec.float("flex_min_hourly", "Mínimo por hora (classic)", G_TARIFAS, 0.0, 500.0, 0.5, "\$/h",
            get = { s().flexMinHourlyRate.toString().toDouble() }, set = { s().flexMinHourlyRate = it.toFloat() }),
        SettingSpec.float("flex_min_block", "Mínimo por bloque (classic)", G_TARIFAS, 0.0, 5000.0, 1.0, "\$",
            get = { s().flexMinBlockPay.toString().toDouble() }, set = { s().flexMinBlockPay = it.toFloat() }),
        SettingSpec.int("flex_min_start_hour", "Hora mínima de inicio (classic)", G_TARIFAS, 0, 23, "h",
            get = { s().flexMinStartHour }, set = { s().flexMinStartHour = it }),

        // ── Alertas ──
        SettingSpec.bool("flex_alerts_enabled", "Servicio de alertas Flex", G_ALERTAS,
            get = { s().flexAlertsEnabled }, set = { s().flexAlertsEnabled = it }),
        SettingSpec.int("alert_volume", "Volumen de alertas", G_ALERTAS, 0, 100, "%",
            get = { s().alertVolume }, set = { s().alertVolume = it }),
        SettingSpec.bool("alert_force_volume", "Forzar volumen al alertar", G_ALERTAS,
            get = { s().alertForceVolumeEnabled }, set = { s().alertForceVolumeEnabled = it }),
        SettingSpec.bool("vibrate_on_alert", "Vibrar con alertas", G_ALERTAS,
            get = { s().vibrateOnAlert }, set = { s().vibrateOnAlert = it }),
        SettingSpec.bool("call_on_block_enabled", "Llamar al tomar bloque", G_ALERTAS,
            get = { s().callOnBlockEnabled }, set = { s().callOnBlockEnabled = it }),
        SettingSpec.text("call_on_block_phone", "Teléfono para llamada", G_ALERTAS,
            get = { s().callOnBlockPhoneNumber }, set = { s().callOnBlockPhoneNumber = it }),
        SettingSpec.bool("call_on_block_when_accepted", "Llamar al aceptar", G_ALERTAS,
            get = { s().callOnBlockWhenAccepted }, set = { s().callOnBlockWhenAccepted = it }),
        SettingSpec.bool("call_on_block_on_scheduled", "Llamar con notificación Scheduled", G_ALERTAS,
            get = { s().callOnBlockOnScheduledNotification }, set = { s().callOnBlockOnScheduledNotification = it }),
        SettingSpec.long("call_on_block_delay_ms", "Retraso de la llamada", G_ALERTAS, 0, 10_000, "ms",
            get = { s().callOnBlockDelayMs }, set = { s().callOnBlockDelayMs = it }),
    )
}
