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
import com.oceanlab.pichix.service.PichixAccessibilityService
import com.oceanlab.pichix.service.PichixForegroundService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object PichixDashboard : DashboardBridge {

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
            // Una sola recarga: el motor relee AppSettings en cada tick; esto reprograma los timers.
            PichixAccessibilityService.syncEngine(app)
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
    private fun s() = AppSettings(app)

    private fun buildSpecs(): List<SettingSpec> = listOf(
        // Comportamiento
        SettingSpec.bool("flex_auto_accept", "Aceptar automáticamente", "Comportamiento",
            get = { s().flexAutoAccept }, set = { s().flexAutoAccept = it }),
        SettingSpec.bool("dry_run", "Modo simulación (no acepta de verdad)", "Comportamiento",
            get = { s().dryRunMode }, set = { s().dryRunMode = it }),
        SettingSpec.bool("auto_pause_accept", "Pausar tras aceptar", "Comportamiento",
            get = { s().autoPauseAfterAccept }, set = { s().autoPauseAfterAccept = it }),
        SettingSpec.bool("flex_continue_on_take_miss", "Seguir activo tras PERDIDA", "Comportamiento",
            get = { s().flexContinueOnTakeMiss }, set = { s().flexContinueOnTakeMiss = it }),
        SettingSpec.bool("auto_pause_captcha", "Pausar si aparece captcha", "Comportamiento",
            get = { s().autoPauseOnCaptcha }, set = { s().autoPauseOnCaptcha = it }),
        SettingSpec.bool("auto_pause_reserved", "Pausar con notificación de bloque reservado", "Comportamiento",
            get = { s().autoPauseOnReservedNotification }, set = { s().autoPauseOnReservedNotification = it }),
        SettingSpec.bool("flex_only_foreground", "Solo con Flex en primer plano", "Comportamiento",
            get = { s().flexOnlyWhenForeground }, set = { s().flexOnlyWhenForeground = it }),
        // Criterios
        SettingSpec.float("flex_min_hourly", "Mínimo por hora", "Criterios", 0.0, 500.0, 0.5, "\$/h",
            get = { s().flexMinHourlyRate.toString().toDouble() }, set = { s().flexMinHourlyRate = it.toFloat() }),
        SettingSpec.float("flex_min_block", "Mínimo por bloque", "Criterios", 0.0, 5000.0, 1.0, "\$",
            get = { s().flexMinBlockPay.toString().toDouble() }, set = { s().flexMinBlockPay = it.toFloat() }),
        SettingSpec.int("flex_min_start_hour", "Hora mínima de inicio", "Criterios", 0, 23, "h",
            get = { s().flexMinStartHour }, set = { s().flexMinStartHour = it }),
        SettingSpec.choice("flex_tariff_mode", "Modo de tarifas", "Criterios",
            listOf(AppSettings.TARIFF_MODE_CLASSIC, AppSettings.TARIFF_MODE_DETAILED),
            help = "classic = criterios rápidos; detailed = reglas por estación",
            get = { s().flexTariffMode }, set = { s().flexTariffMode = it }),
        SettingSpec.choice("flex_offer_pick_mode", "Qué oferta tomar", "Criterios",
            listOf(AppSettings.OFFER_PICK_FIRST, AppSettings.OFFER_PICK_BEST),
            help = "first = la primera válida; best = la mejor según el criterio",
            get = { s().flexOfferPickMode }, set = { s().flexOfferPickMode = it }),
        SettingSpec.choice("flex_offer_rank_criterion", "Criterio de «mejor»", "Criterios",
            listOf(AppSettings.OFFER_RANK_HOURLY, AppSettings.OFFER_RANK_BLOCK_PAY, AppSettings.OFFER_RANK_DURATION_MIN,
                AppSettings.OFFER_RANK_DURATION_MAX, AppSettings.OFFER_RANK_START_SOONEST),
            get = { s().flexOfferRankCriterion }, set = { s().flexOfferRankCriterion = it }),
        // Clics y refresco
        SettingSpec.bool("flex_click_refresh_enabled", "Pulsar Refresh en cada ciclo", "Clics y refresco",
            get = { s().flexClickRefreshEnabled }, set = { s().flexClickRefreshEnabled = it }),
        SettingSpec.bool("flex_reanalyze_after_refresh", "Reanalizar tras Refresh", "Clics y refresco",
            get = { s().flexReanalyzeAfterRefreshEnabled }, set = { s().flexReanalyzeAfterRefreshEnabled = it }),
        SettingSpec.bool("flex_auto_scroll_enabled", "Scroll automático en la lista", "Clics y refresco",
            get = { s().flexAutoScrollEnabled }, set = { s().flexAutoScrollEnabled = it }),
        SettingSpec.choice("flex_click_mode", "Modo de clic", "Clics y refresco",
            listOf(AppSettings.CLICK_MODE_BASIC, AppSettings.CLICK_MODE_SMART),
            help = "basic = intervalo fijo; smart = espera aleatoria entre mín y máx",
            get = { s().flexClickMode }, set = { s().flexClickMode = it }),
        SettingSpec.long("flex_grab_interval_ms", "Intervalo (modo basic)", "Clics y refresco", 800, 60_000, "ms",
            get = { s().flexGrabIntervalMs }, set = { s().flexGrabIntervalMs = it }),
        SettingSpec.int("flex_smart_click_min_sec", "Espera mínima (smart)", "Clics y refresco", 1, 3600, "s",
            get = { s().flexSmartClickMinSec }, set = { s().flexSmartClickMinSec = it }),
        SettingSpec.int("flex_smart_click_max_sec", "Espera máxima (smart)", "Clics y refresco", 1, 3600, "s",
            get = { s().flexSmartClickMaxSec }, set = { s().flexSmartClickMaxSec = it }),
        // Ráfagas
        SettingSpec.bool("flex_burst_click_enabled", "Ráfagas de Refresh", "Ráfagas",
            get = { s().flexBurstClickEnabled }, set = { s().flexBurstClickEnabled = it }),
        SettingSpec.int("flex_burst_interval_min_min", "Entre ráfagas (mín)", "Ráfagas", 1, 1440, "min",
            get = { s().flexBurstIntervalMinMin }, set = { s().flexBurstIntervalMinMin = it }),
        SettingSpec.int("flex_burst_interval_max_min", "Entre ráfagas (máx)", "Ráfagas", 1, 1440, "min",
            get = { s().flexBurstIntervalMaxMin }, set = { s().flexBurstIntervalMaxMin = it }),
        SettingSpec.long("flex_burst_click_interval_ms", "Entre clics de la ráfaga", "Ráfagas", 100, 10_000, "ms",
            get = { s().flexBurstClickIntervalMs }, set = { s().flexBurstClickIntervalMs = it }),
        // Volver a ofertas
        SettingSpec.bool("flex_auto_return_offers", "Volver a ofertas automáticamente", "Volver a ofertas",
            get = { s().flexAutoReturnToOffers }, set = { s().flexAutoReturnToOffers = it }),
        // Alertas
        SettingSpec.bool("flex_alerts_enabled", "Alertas por notificación Flex", "Alertas",
            get = { s().flexAlertsEnabled }, set = { s().flexAlertsEnabled = it }),
        SettingSpec.int("alert_volume", "Volumen de alertas", "Alertas", 0, 100, "%",
            get = { s().alertVolume }, set = { s().alertVolume = it }),
        SettingSpec.bool("vibrate_on_alert", "Vibrar con alertas", "Alertas",
            get = { s().vibrateOnAlert }, set = { s().vibrateOnAlert = it }),
        SettingSpec.bool("call_on_block_enabled", "Llamar al tomar bloque", "Alertas",
            get = { s().callOnBlockEnabled }, set = { s().callOnBlockEnabled = it }),
    )
}
