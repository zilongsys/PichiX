package com.oceanlab.pichix.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class AppSettings(context: Context) {

    data class RestoreSkippedEntry(
        val key: String,
        val reason: String,
    )

    data class RestoreBackupResult(
        val appliedKeys: List<String>,
        val skipped: List<RestoreSkippedEntry>,
        val clearedLocalKeys: Int,
    )

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isBotEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BOT_ENABLED, value).apply()

    var useDarkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK_THEME, value).apply()

    var dryRunMode: Boolean
        get() = prefs.getBoolean(KEY_DRY_RUN, false)
        set(value) = prefs.edit().putBoolean(KEY_DRY_RUN, value).apply()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    /** FAB flotante ⏸ para pausar el motor (clics, scroll, Return 2) sin apagar el bot. */
    var overlayMotorPauseFabEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_MOTOR_PAUSE, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_MOTOR_PAUSE, value).apply()

    /** FAB flotante ↩ para probar manualmente el flujo Return 2 offers. */
    var overlayTestReturnEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_TEST_RETURN, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_TEST_RETURN, value).apply()

    fun hasAnyOverlayFab(): Boolean =
        overlayEnabled || overlayMotorPauseFabEnabled || overlayTestReturnEnabled

    var showCategoryNames: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CATEGORY_NAMES, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CATEGORY_NAMES, value).apply()

    /** Paquete(s) vigilados, separados por coma. */
    var monitorPackagesCsv: String
        get() = prefs.getString(KEY_MONITOR_PACKAGES, DEFAULT_FLEX_PACKAGE) ?: DEFAULT_FLEX_PACKAGE
        set(value) = prefs.edit().putString(KEY_MONITOR_PACKAGES, value.trim()).apply()

    /** Mínimo $/hora (Terminator-Grabber). */
    var flexMinHourlyRate: Float
        get() = prefs.getFloat(KEY_MIN_HOURLY, 23f)
        set(value) = prefs.edit().putFloat(KEY_MIN_HOURLY, value).apply()

    /** Mínimo $ por bloque. */
    var flexMinBlockPay: Float
        get() = prefs.getFloat(KEY_MIN_BLOCK, 119f)
        set(value) = prefs.edit().putFloat(KEY_MIN_BLOCK, value).apply()

    /** Hora mínima de inicio del bloque (0–23). */
    var flexMinStartHour: Int
        get() = prefs.getInt(KEY_MIN_START_HOUR, 6)
        set(value) = prefs.edit().putInt(KEY_MIN_START_HOUR, value.coerceIn(0, 23)).apply()

    /** Config → Clics: pulsar Refresh al inicio de cada ciclo (independiente de tarifas). */
    var flexClickRefreshEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLICK_REFRESH_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CLICK_REFRESH_ENABLED, value).apply()

    /** Tras pulsar Refresh: releer pantalla y evaluar ofertas de nuevo (sin esperar al siguiente ciclo). */
    var flexReanalyzeAfterRefreshEnabled: Boolean
        get() = prefs.getBoolean(KEY_REANALYZE_AFTER_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_REANALYZE_AFTER_REFRESH, value).apply()

    /** Config → Clics: scroll automático en lista (gesto en zona, ping-pong arriba/abajo). */
    var flexAutoScrollEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCROLL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SCROLL_ENABLED, value).apply()

    /** first = primera válida en lista; best = mejor según [flexOfferRankCriterion]. */
    var flexOfferPickMode: String
        get() = prefs.getString(KEY_OFFER_PICK_MODE, OFFER_PICK_BEST) ?: OFFER_PICK_BEST
        set(value) = prefs.edit().putString(KEY_OFFER_PICK_MODE, value).apply()

    var flexOfferRankCriterion: String
        get() = prefs.getString(KEY_OFFER_RANK_CRITERION, OFFER_RANK_HOURLY) ?: OFFER_RANK_HOURLY
        set(value) = prefs.edit().putString(KEY_OFFER_RANK_CRITERION, value).apply()

    /** Historial: misma oferta (estación+$+hora+duración) no se repite en CSV dentro de esta ventana (ms). */
    var dedupWindowMs: Long
        get() = prefs.getLong(KEY_DEDUP_WINDOW_MS, 90 * 60 * 1000L).coerceIn(5_000L, 4 * 60 * 60 * 1000L)
        set(value) = prefs.edit().putLong(KEY_DEDUP_WINDOW_MS, value.coerceIn(5_000L, 4 * 60 * 60 * 1000L)).apply()

    /** URI SAF para export TXT del historial. */
    var txtSaveUri: String
        get() = prefs.getString(KEY_TXT_SAVE_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TXT_SAVE_URI, value).apply()

    fun usesBestOfferPick(): Boolean = flexOfferPickMode == OFFER_PICK_BEST

    fun offerRankLabel(): String = when (flexOfferRankCriterion) {
        OFFER_RANK_BLOCK_PAY -> "\$/bloque"
        OFFER_RANK_DURATION_MIN -> "duración mín."
        OFFER_RANK_DURATION_MAX -> "duración máx."
        OFFER_RANK_START_SOONEST -> "empieza antes"
        else -> "\$/h"
    }

    init {
        migrateLegacyOnlyRefreshFlag()
        migrateAutoScrollFromDisable()
    }

    private fun migrateLegacyOnlyRefreshFlag() {
        if (prefs.getBoolean(KEY_ONLY_REFRESH_SPLIT_MIGRATION, false)) return
        val legacy = prefs.getBoolean(KEY_ONLY_REFRESH, false)
        prefs.edit()
            .putBoolean(KEY_CLICK_REFRESH_ENABLED, legacy)
            .putBoolean(KEY_DISABLE_LIST_SCROLL, legacy)
            .putBoolean(KEY_ONLY_REFRESH_SPLIT_MIGRATION, true)
            .apply()
    }

    private fun migrateAutoScrollFromDisable() {
        if (prefs.getBoolean(KEY_AUTO_SCROLL_MIGRATION, false)) return
        val hadDisable = prefs.getBoolean(KEY_DISABLE_LIST_SCROLL, false)
        prefs.edit()
            .putBoolean(KEY_AUTO_SCROLL_ENABLED, !hadDisable)
            .putBoolean(KEY_AUTO_SCROLL_MIGRATION, true)
            .apply()
    }

    var flexCancelBadBlocks: Boolean
        get() = prefs.getBoolean(KEY_CANCEL_BAD_BLOCKS, false)
        set(value) = prefs.edit().putBoolean(KEY_CANCEL_BAD_BLOCKS, value).apply()

    /** Intervalo del bucle Terminator-Grabber (ms) — modo Basic click. */
    var flexGrabIntervalMs: Long
        get() = prefs.getLong(KEY_GRAB_INTERVAL_MS, 2500L)
        set(value) = prefs.edit().putLong(KEY_GRAB_INTERVAL_MS, value.coerceIn(800L, 60_000L)).apply()

    /** basic = intervalo fijo; smart = espera aleatoria entre min y max (segundos). */
    var flexClickMode: String
        get() = prefs.getString(KEY_FLEX_CLICK_MODE, CLICK_MODE_BASIC) ?: CLICK_MODE_BASIC
        set(value) = prefs.edit().putString(KEY_FLEX_CLICK_MODE, value).apply()

    var flexSmartClickMinSec: Int
        get() = prefs.getInt(KEY_FLEX_SMART_MIN_SEC, 1).coerceIn(1, 3600)
        set(value) = prefs.edit().putInt(KEY_FLEX_SMART_MIN_SEC, value.coerceIn(1, 3600)).apply()

    var flexSmartClickMaxSec: Int
        get() = prefs.getInt(KEY_FLEX_SMART_MAX_SEC, 6).coerceIn(1, 3600)
        set(value) = prefs.edit().putInt(KEY_FLEX_SMART_MAX_SEC, value.coerceIn(1, 3600)).apply()

    /** Texto exacto del botón a pulsar (ej. Refresh). */
    var flexRefreshButtonText: String
        get() = prefs.getString(KEY_FLEX_REFRESH_BUTTON_TEXT, DEFAULT_REFRESH_BUTTON) ?: DEFAULT_REFRESH_BUTTON
        set(value) = prefs.edit().putString(KEY_FLEX_REFRESH_BUTTON_TEXT, value.trim()).apply()

    /** Fragmento que debe aparecer en pantalla para aplicar el clic (ej. Offers). Vacío = cualquier pantalla. */
    var flexClickScreenText: String
        get() = prefs.getString(KEY_FLEX_CLICK_SCREEN_TEXT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FLEX_CLICK_SCREEN_TEXT, value.trim()).apply()

    /** contains | exact — texto de la pantalla de ofertas. */
    var flexClickScreenMatchMode: String
        get() = normalizeMatchMode(prefs.getString(KEY_FLEX_CLICK_SCREEN_MODE, TEXT_MATCH_CONTAINS))
        set(value) = prefs.edit().putString(KEY_FLEX_CLICK_SCREEN_MODE, normalizeMatchMode(value)).apply()

    /** false = sensible a mayúsculas (por defecto en pantalla de ofertas). */
    var flexClickScreenIgnoreCase: Boolean
        get() = prefs.getBoolean(KEY_FLEX_CLICK_SCREEN_IGNORE_CASE, false)
        set(value) = prefs.edit().putBoolean(KEY_FLEX_CLICK_SCREEN_IGNORE_CASE, value).apply()

    var flexRefreshButtonMatchMode: String
        get() = normalizeMatchMode(prefs.getString(KEY_FLEX_REFRESH_BUTTON_MODE, TEXT_MATCH_EXACT))
        set(value) = prefs.edit().putString(KEY_FLEX_REFRESH_BUTTON_MODE, normalizeMatchMode(value)).apply()

    var flexRefreshButtonIgnoreCase: Boolean
        get() = prefs.getBoolean(KEY_FLEX_REFRESH_BUTTON_IGNORE_CASE, true)
        set(value) = prefs.edit().putBoolean(KEY_FLEX_REFRESH_BUTTON_IGNORE_CASE, value).apply()

    var pauseByOverClicksMatchMode: String
        get() = normalizeMatchMode(prefs.getString(KEY_PAUSE_MATCH_MODE, TEXT_MATCH_CONTAINS))
        set(value) = prefs.edit().putString(KEY_PAUSE_MATCH_MODE, normalizeMatchMode(value)).apply()

    var pauseByOverClicksIgnoreCase: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_IGNORE_CASE, true)
        set(value) = prefs.edit().putBoolean(KEY_PAUSE_IGNORE_CASE, value).apply()

    /** Si true, grabber/scroll/clics/return solo con Amazon Flex en primer plano. */
    var flexOnlyWhenForeground: Boolean
        get() = prefs.getBoolean(KEY_FLEX_ONLY_FOREGROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_FLEX_ONLY_FOREGROUND, value).apply()

    fun isConfigSectionExpanded(sectionKey: String, defaultExpanded: Boolean = true): Boolean {
        val key = KEY_CONFIG_SECTION_PREFIX + sectionKey
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, defaultExpanded)
        } else {
            defaultExpanded
        }
    }

    fun setConfigSectionExpanded(sectionKey: String, expanded: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIG_SECTION_PREFIX + sectionKey, expanded).apply()
    }

    var configPhaseNoteVisible: Boolean
        get() = prefs.getBoolean(KEY_CONFIG_PHASE_NOTE_VISIBLE, false)
        set(value) = prefs.edit().putBoolean(KEY_CONFIG_PHASE_NOTE_VISIBLE, value).apply()

    fun isConfigHintVisible(hintKey: String, defaultVisible: Boolean = false): Boolean {
        val key = KEY_CONFIG_HINT_PREFIX + hintKey
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, defaultVisible)
        } else {
            defaultVisible
        }
    }

    fun setConfigHintVisible(hintKey: String, visible: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIG_HINT_PREFIX + hintKey, visible).apply()
    }

    var debugLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    var fileLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILE_LOG, true)
        set(value) = prefs.edit().putBoolean(KEY_FILE_LOG, value).apply()

    fun nextGrabDelayMs(): Long {
        if (flexClickMode == CLICK_MODE_SMART) {
            val min = flexSmartClickMinSec.coerceAtLeast(1)
            val max = flexSmartClickMaxSec.coerceAtLeast(min)
            return (min..max).random().toLong() * 1000L
        }
        return flexGrabIntervalMs
    }

    fun nextReturnStepDelayMs(): Long {
        val min = flexReturnStepMinSec.coerceAtLeast(0)
        val max = flexReturnStepMaxSec.coerceAtLeast(min)
        return (min..max).random().toLong() * 1000L
    }

    fun nextBurstIntervalMs(): Long {
        val min = flexBurstIntervalMinMin.coerceAtLeast(1)
        val max = flexBurstIntervalMaxMin.coerceAtLeast(min)
        return (min..max).random().toLong() * 60_000L
    }

    fun nextBurstDurationMs(): Long {
        val min = flexBurstDurationMinSec.coerceAtLeast(5)
        val max = flexBurstDurationMaxSec.coerceAtLeast(min)
        return (min..max).random().toLong() * 1000L
    }

    var autoPauseOnCaptcha: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE_CAPTCHA, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PAUSE_CAPTCHA, value).apply()

    var autoPauseAfterAccept: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE_ACCEPT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PAUSE_ACCEPT, value).apply()

    /** Si true (defecto), tras PERDIDA al intentar tomar el bloque el bot sigue activo. */
    var flexContinueOnTakeMiss: Boolean
        get() = prefs.getBoolean(KEY_FLEX_CONTINUE_ON_TAKE_MISS, true)
        set(value) = prefs.edit().putBoolean(KEY_FLEX_CONTINUE_ON_TAKE_MISS, value).apply()

    /**
     * Si true: abre la oferta y en Offer Details pulsa Schedule.
     * Si false (o modo simulación en Home): solo abre detalle y se detiene ahí.
     */
    var flexAutoAccept: Boolean
        get() = prefs.getBoolean(KEY_FLEX_AUTO_ACCEPT, true)
        set(value) = prefs.edit().putBoolean(KEY_FLEX_AUTO_ACCEPT, value).apply()

    var autoPauseOnReservedNotification: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE_RESERVED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PAUSE_RESERVED, value).apply()

    var flexAutoReturnToOffers: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RETURN_OFFERS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RETURN_OFFERS, value).apply()

    /** Espera aleatoria (seg) entre pasos del macro Return 2 (menú → Offers → verificar). */
    var flexReturnStepMinSec: Int
        get() = prefs.getInt(KEY_RETURN_STEP_MIN_SEC, 1).coerceIn(0, 60)
        set(value) = prefs.edit().putInt(KEY_RETURN_STEP_MIN_SEC, value.coerceIn(0, 60)).apply()

    var flexReturnStepMaxSec: Int
        get() = prefs.getInt(KEY_RETURN_STEP_MAX_SEC, 3).coerceIn(0, 60)
        set(value) = prefs.edit().putInt(KEY_RETURN_STEP_MAX_SEC, value.coerceIn(0, 60)).apply()

    /** Mínimo entre intentos automáticos de Return 2 al detectar pantalla fuera de ofertas. */
    var flexReturnDetectCooldownSec: Int
        get() = prefs.getInt(KEY_RETURN_DETECT_COOLDOWN_SEC, 3).coerceIn(1, 120)
        set(value) = prefs.edit().putInt(KEY_RETURN_DETECT_COOLDOWN_SEC, value.coerceIn(1, 120)).apply()

    /** JSON de [FlexReturnScreenTrigger] para detectar pantalla fuera de ofertas. */
    var flexReturnTriggersJson: String
        get() = prefs.getString(KEY_RETURN_TRIGGERS_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RETURN_TRIGGERS_JSON, value).apply()

    fun hasReturnTriggersConfigured(): Boolean = prefs.contains(KEY_RETURN_TRIGGERS_JSON)

    /** Ráfaga de clics Refresh: intervalo aleatorio entre ráfagas (minutos). */
    var flexBurstClickEnabled: Boolean
        get() = prefs.getBoolean(KEY_BURST_CLICK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BURST_CLICK_ENABLED, value).apply()

    var flexBurstIntervalMinMin: Int
        get() = prefs.getInt(KEY_BURST_INTERVAL_MIN_MIN, 5).coerceIn(1, 24 * 60)
        set(value) = prefs.edit().putInt(KEY_BURST_INTERVAL_MIN_MIN, value.coerceIn(1, 24 * 60)).apply()

    var flexBurstIntervalMaxMin: Int
        get() = prefs.getInt(KEY_BURST_INTERVAL_MAX_MIN, 15).coerceIn(1, 24 * 60)
        set(value) = prefs.edit().putInt(KEY_BURST_INTERVAL_MAX_MIN, value.coerceIn(1, 24 * 60)).apply()

    /** Intervalo entre clics durante la ráfaga (ms). */
    var flexBurstClickIntervalMs: Long
        get() = prefs.getLong(KEY_BURST_CLICK_INTERVAL_MS, 500L).coerceIn(100L, 10_000L)
        set(value) = prefs.edit().putLong(KEY_BURST_CLICK_INTERVAL_MS, value.coerceIn(100L, 10_000L)).apply()

    /** Duración aleatoria de cada ráfaga (segundos, mín – máx). */
    var flexBurstDurationMinSec: Int
        get() = readBurstDurationMin()
        set(value) = prefs.edit().putInt(KEY_BURST_DURATION_MIN_SEC, value.coerceIn(5, 3600)).apply()

    var flexBurstDurationMaxSec: Int
        get() = readBurstDurationMax()
        set(value) = prefs.edit().putInt(KEY_BURST_DURATION_MAX_SEC, value.coerceIn(5, 3600)).apply()

    private fun readBurstDurationMin(): Int {
        if (!prefs.contains(KEY_BURST_DURATION_MIN_SEC)) {
            return prefs.getInt(KEY_BURST_DURATION_SEC, 20).coerceIn(5, 3600)
        }
        return prefs.getInt(KEY_BURST_DURATION_MIN_SEC, 20).coerceIn(5, 3600)
    }

    private fun readBurstDurationMax(): Int {
        if (!prefs.contains(KEY_BURST_DURATION_MAX_SEC)) {
            return prefs.getInt(KEY_BURST_DURATION_SEC, 40).coerceIn(5, 3600)
        }
        return prefs.getInt(KEY_BURST_DURATION_MAX_SEC, 40).coerceIn(5, 3600)
    }

    /** Pausa el bot al detectar notificación con texto configurado (Pause by over clicks). */
    var pauseByOverClicksEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_OVER_CLICKS, false)
        set(value) = prefs.edit().putBoolean(KEY_PAUSE_OVER_CLICKS, value).apply()

    var pauseByOverClicksMatchText: String
        get() = prefs.getString(KEY_PAUSE_OVER_CLICKS_TEXT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAUSE_OVER_CLICKS_TEXT, value.trim()).apply()

    var pauseByOverClicksPauseSoundUri: String
        get() = prefs.getString(KEY_PAUSE_OVER_CLICKS_PAUSE_SOUND, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAUSE_OVER_CLICKS_PAUSE_SOUND, value).apply()

    var pauseByOverClicksResumeSoundUri: String
        get() = prefs.getString(KEY_PAUSE_OVER_CLICKS_RESUME_SOUND, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAUSE_OVER_CLICKS_RESUME_SOUND, value).apply()

    /** Sonido al detectar oferta válida y pulsar su tarjeta en la lista. */
    var offerClickSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_OFFER_CLICK_SOUND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFER_CLICK_SOUND_ENABLED, value).apply()

    var offerClickSoundUri: String
        get() = prefs.getString(KEY_OFFER_CLICK_SOUND_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OFFER_CLICK_SOUND_URI, value).apply()

    /** Repeticiones del sonido al pulsar oferta (1–20). */
    var offerClickSoundRepeatCount: Int
        get() = prefs.getInt(KEY_OFFER_CLICK_SOUND_REPEAT, 1).coerceIn(1, 20)
        set(value) = prefs.edit().putInt(KEY_OFFER_CLICK_SOUND_REPEAT, value.coerceIn(1, 20)).apply()


    /**
     * Si lista y detalle no coinciden en Offer Details (pantalla del botón Schedule):
     * [MISMATCH_ACTION_SOUND_STAY] = sonido de aviso y quedarse en detalle;
     * [MISMATCH_ACTION_AUTO_CANCEL] = pulsar Cancel y confirmar.
     */
    var flexDetailMismatchAction: String
        get() = prefs.getString(KEY_FLEX_DETAIL_MISMATCH_ACTION, MISMATCH_ACTION_SOUND_STAY)
            ?: MISMATCH_ACTION_SOUND_STAY
        set(value) = prefs.edit().putString(KEY_FLEX_DETAIL_MISMATCH_ACTION, value).apply()

    var flexDetailMismatchSoundUri: String
        get() = prefs.getString(KEY_FLEX_DETAIL_MISMATCH_SOUND_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FLEX_DETAIL_MISMATCH_SOUND_URI, value).apply()

    /** Minutos de espera antes de reanudar el bot automáticamente. */
    var pauseByOverClicksResumeMinutes: Int
        get() = prefs.getInt(KEY_PAUSE_OVER_CLICKS_MINUTES, 5).coerceIn(1, 24 * 60)
        set(value) = prefs.edit().putInt(KEY_PAUSE_OVER_CLICKS_MINUTES, value.coerceIn(1, 24 * 60)).apply()

    var overlayPosX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_X, value).apply()

    var overlayPosY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()

    /** classic = criterios rápidos; detailed = reglas por estación (estilo Spark). */
    var flexTariffMode: String
        get() = prefs.getString(KEY_FLEX_TARIFF_MODE, TARIFF_MODE_CLASSIC) ?: TARIFF_MODE_CLASSIC
        set(value) = prefs.edit().putString(KEY_FLEX_TARIFF_MODE, value).apply()

    var flexTariffRulesJson: String
        get() = prefs.getString(KEY_FLEX_TARIFF_RULES_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FLEX_TARIFF_RULES_JSON, value).apply()

    var showFlexTariffModeInfo: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FLEX_TARIFF_INFO, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_FLEX_TARIFF_INFO, value).apply()

    /** Reglas de alerta por notificación Flex (pestaña Alertas). */
    var flexAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLEX_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_FLEX_ALERTS_ENABLED, value).apply()

    var flexAlertRulesJson: String
        get() = prefs.getString(KEY_FLEX_ALERT_RULES_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FLEX_ALERT_RULES_JSON, value).apply()

    var alertVolume: Int
        get() = prefs.getInt(KEY_ALERT_VOLUME, 80).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_ALERT_VOLUME, value.coerceIn(0, 100)).apply()

    /** Sube volumen del sistema y usa canal de alarma al reproducir alertas Flex. */
    var alertForceVolumeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERT_FORCE_VOLUME, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_FORCE_VOLUME, value).apply()

    var vibrateOnAlert: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE_ON_ALERT, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE_ON_ALERT, value).apply()

    /** Llamada automática al tomar bloque o al coincidir alerta con «Llamar» activo. */
    var callOnBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALL_ON_BLOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CALL_ON_BLOCK_ENABLED, value).apply()

    var callOnBlockPhoneNumber: String
        get() = prefs.getString(KEY_CALL_ON_BLOCK_PHONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALL_ON_BLOCK_PHONE, value.trim()).apply()

    /** Llamar cuando el bot pulsa Schedule con éxito. */
    var callOnBlockWhenAccepted: Boolean
        get() = prefs.getBoolean(KEY_CALL_ON_BLOCK_WHEN_ACCEPTED, true)
        set(value) = prefs.edit().putBoolean(KEY_CALL_ON_BLOCK_WHEN_ACCEPTED, value).apply()

    /** Llamar con la notificación genérica «bloque programado» de Flex. */
    var callOnBlockOnScheduledNotification: Boolean
        get() = prefs.getBoolean(KEY_CALL_ON_BLOCK_ON_SCHEDULED, false)
        set(value) = prefs.edit().putBoolean(KEY_CALL_ON_BLOCK_ON_SCHEDULED, value).apply()

    /** Espera (ms) antes de marcar: aplica a Schedule, notificación programada y alertas con «Llamar». */
    var callOnBlockDelayMs: Long
        get() {
            if (prefs.contains(KEY_CALL_ON_BLOCK_DELAY_MS)) {
                return prefs.getLong(KEY_CALL_ON_BLOCK_DELAY_MS, 0L).coerceIn(0L, 10_000L)
            }
            if (prefs.contains(KEY_FLEX_OFFER_TAKE_DETAIL_DELAY_MS)) {
                return prefs.getLong(KEY_FLEX_OFFER_TAKE_DETAIL_DELAY_MS, 0L).coerceIn(0L, 10_000L)
            }
            return 0L
        }
        set(value) = prefs.edit().putLong(KEY_CALL_ON_BLOCK_DELAY_MS, value.coerceIn(0L, 10_000L)).apply()

    fun usesFlexDetailedTariff(): Boolean = flexTariffMode == TARIFF_MODE_DETAILED

    fun usesDetailMismatchAutoCancel(): Boolean =
        flexDetailMismatchAction == MISMATCH_ACTION_AUTO_CANCEL

    /** Claves de UI (secciones/ayudas plegables) incluidas en respaldo. */
    private fun uiPreferenceEntries(): Map<String, Any> =
        prefs.all.filterKeys { key ->
            key.startsWith(KEY_CONFIG_SECTION_PREFIX) || key.startsWith(KEY_CONFIG_HINT_PREFIX)
        }.mapValues { (_, value) -> value as Any }

    /** Valores efectivos de todos los ajustes (para respaldo/importación). */
    fun snapshotForBackup(): Map<String, Any> = coreSnapshotForBackup() + uiPreferenceEntries()

    private fun coreSnapshotForBackup(): Map<String, Any> = mapOf(
        KEY_BOT_ENABLED to isBotEnabled,
        KEY_DARK_THEME to useDarkTheme,
        KEY_DRY_RUN to dryRunMode,
        KEY_OVERLAY to overlayEnabled,
        KEY_OVERLAY_MOTOR_PAUSE to overlayMotorPauseFabEnabled,
        KEY_OVERLAY_TEST_RETURN to overlayTestReturnEnabled,
        KEY_SHOW_CATEGORY_NAMES to showCategoryNames,
        KEY_MONITOR_PACKAGES to monitorPackagesCsv,
        KEY_MIN_HOURLY to flexMinHourlyRate,
        KEY_MIN_BLOCK to flexMinBlockPay,
        KEY_MIN_START_HOUR to flexMinStartHour,
        KEY_CLICK_REFRESH_ENABLED to flexClickRefreshEnabled,
        KEY_REANALYZE_AFTER_REFRESH to flexReanalyzeAfterRefreshEnabled,
        KEY_AUTO_SCROLL_ENABLED to flexAutoScrollEnabled,
        KEY_OFFER_PICK_MODE to flexOfferPickMode,
        KEY_OFFER_RANK_CRITERION to flexOfferRankCriterion,
        KEY_DEDUP_WINDOW_MS to dedupWindowMs,
        KEY_TXT_SAVE_URI to txtSaveUri,
        KEY_CANCEL_BAD_BLOCKS to flexCancelBadBlocks,
        KEY_GRAB_INTERVAL_MS to flexGrabIntervalMs,
        KEY_FLEX_CLICK_MODE to flexClickMode,
        KEY_FLEX_SMART_MIN_SEC to flexSmartClickMinSec,
        KEY_FLEX_SMART_MAX_SEC to flexSmartClickMaxSec,
        KEY_FLEX_REFRESH_BUTTON_TEXT to flexRefreshButtonText,
        KEY_FLEX_CLICK_SCREEN_TEXT to flexClickScreenText,
        KEY_FLEX_CLICK_SCREEN_MODE to flexClickScreenMatchMode,
        KEY_FLEX_CLICK_SCREEN_IGNORE_CASE to flexClickScreenIgnoreCase,
        KEY_FLEX_REFRESH_BUTTON_MODE to flexRefreshButtonMatchMode,
        KEY_FLEX_REFRESH_BUTTON_IGNORE_CASE to flexRefreshButtonIgnoreCase,
        KEY_PAUSE_MATCH_MODE to pauseByOverClicksMatchMode,
        KEY_PAUSE_IGNORE_CASE to pauseByOverClicksIgnoreCase,
        KEY_FLEX_ONLY_FOREGROUND to flexOnlyWhenForeground,
        KEY_CONFIG_PHASE_NOTE_VISIBLE to configPhaseNoteVisible,
        KEY_DEBUG_LOG to debugLogEnabled,
        KEY_FILE_LOG to fileLogEnabled,
        KEY_AUTO_PAUSE_CAPTCHA to autoPauseOnCaptcha,
        KEY_AUTO_PAUSE_ACCEPT to autoPauseAfterAccept,
        KEY_FLEX_CONTINUE_ON_TAKE_MISS to flexContinueOnTakeMiss,
        KEY_FLEX_AUTO_ACCEPT to flexAutoAccept,
        KEY_AUTO_PAUSE_RESERVED to autoPauseOnReservedNotification,
        KEY_AUTO_RETURN_OFFERS to flexAutoReturnToOffers,
        KEY_RETURN_STEP_MIN_SEC to flexReturnStepMinSec,
        KEY_RETURN_STEP_MAX_SEC to flexReturnStepMaxSec,
        KEY_RETURN_DETECT_COOLDOWN_SEC to flexReturnDetectCooldownSec,
        KEY_RETURN_TRIGGERS_JSON to flexReturnTriggersJson,
        KEY_BURST_CLICK_ENABLED to flexBurstClickEnabled,
        KEY_BURST_INTERVAL_MIN_MIN to flexBurstIntervalMinMin,
        KEY_BURST_INTERVAL_MAX_MIN to flexBurstIntervalMaxMin,
        KEY_BURST_CLICK_INTERVAL_MS to flexBurstClickIntervalMs,
        KEY_BURST_DURATION_MIN_SEC to flexBurstDurationMinSec,
        KEY_BURST_DURATION_MAX_SEC to flexBurstDurationMaxSec,
        KEY_PAUSE_OVER_CLICKS to pauseByOverClicksEnabled,
        KEY_PAUSE_OVER_CLICKS_TEXT to pauseByOverClicksMatchText,
        KEY_PAUSE_OVER_CLICKS_PAUSE_SOUND to pauseByOverClicksPauseSoundUri,
        KEY_PAUSE_OVER_CLICKS_RESUME_SOUND to pauseByOverClicksResumeSoundUri,
        KEY_OFFER_CLICK_SOUND_ENABLED to offerClickSoundEnabled,
        KEY_OFFER_CLICK_SOUND_URI to offerClickSoundUri,
        KEY_OFFER_CLICK_SOUND_REPEAT to offerClickSoundRepeatCount,
        KEY_FLEX_DETAIL_MISMATCH_ACTION to flexDetailMismatchAction,
        KEY_FLEX_DETAIL_MISMATCH_SOUND_URI to flexDetailMismatchSoundUri,
        KEY_PAUSE_OVER_CLICKS_MINUTES to pauseByOverClicksResumeMinutes,
        KEY_OVERLAY_X to overlayPosX,
        KEY_OVERLAY_Y to overlayPosY,
        KEY_FLEX_TARIFF_MODE to flexTariffMode,
        KEY_FLEX_TARIFF_RULES_JSON to flexTariffRulesJson,
        KEY_SHOW_FLEX_TARIFF_INFO to showFlexTariffModeInfo,
        KEY_FLEX_ALERTS_ENABLED to flexAlertsEnabled,
        KEY_FLEX_ALERT_RULES_JSON to flexAlertRulesJson,
        KEY_ALERT_VOLUME to alertVolume,
        KEY_ALERT_FORCE_VOLUME to alertForceVolumeEnabled,
        KEY_VIBRATE_ON_ALERT to vibrateOnAlert,
        KEY_CALL_ON_BLOCK_ENABLED to callOnBlockEnabled,
        KEY_CALL_ON_BLOCK_PHONE to callOnBlockPhoneNumber,
        KEY_CALL_ON_BLOCK_WHEN_ACCEPTED to callOnBlockWhenAccepted,
        KEY_CALL_ON_BLOCK_ON_SCHEDULED to callOnBlockOnScheduledNotification,
        KEY_CALL_ON_BLOCK_DELAY_MS to callOnBlockDelayMs,
    )

    /** Restaura ajustes desde respaldo JSON (un solo commit). Omite claves inválidas y conserva el valor local previo. */
    fun restoreFromBackup(entries: Map<String, Any>): RestoreBackupResult {
        val previous = prefs.all.filterKeys { it !in PRESERVE_ON_IMPORT }
        val editor = prefs.edit()
        var cleared = 0
        previous.keys.forEach { key ->
            editor.remove(key)
            cleared++
        }
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<RestoreSkippedEntry>()
        entries.forEach { (key, value) ->
            if (key in PRESERVE_ON_IMPORT) return@forEach
            try {
                validateBackupEntry(key, value)
                restoreBackupEntry(editor, key, value)
                applied.add(key)
            } catch (e: Exception) {
                skipped.add(RestoreSkippedEntry(key, e.message ?: "Valor inválido"))
                restorePrefSnapshotEntry(editor, key, previous[key])
            }
        }
        if (applied.isEmpty()) {
            throw IllegalArgumentException(
                if (skipped.isEmpty()) {
                    "No se importó ningún ajuste."
                } else {
                    "No se importó ningún ajuste válido (${skipped.size} omitidos)."
                },
            )
        }
        if (!editor.commit()) {
            throw IllegalStateException("No se pudo guardar la configuración importada.")
        }
        markPendingConfigUiReload()
        return RestoreBackupResult(
            appliedKeys = applied.sorted(),
            skipped = skipped.sortedBy { it.key },
            clearedLocalKeys = cleared,
        )
    }

    fun readBackupDisplayValue(key: String): String = when (key) {
        KEY_RETURN_STEP_MIN_SEC -> flexReturnStepMinSec.toString()
        KEY_RETURN_STEP_MAX_SEC -> flexReturnStepMaxSec.toString()
        KEY_RETURN_DETECT_COOLDOWN_SEC -> flexReturnDetectCooldownSec.toString()
        KEY_MIN_HOURLY -> flexMinHourlyRate.toString()
        KEY_MIN_BLOCK -> flexMinBlockPay.toString()
        KEY_RETURN_TRIGGERS_JSON,
        KEY_FLEX_TARIFF_RULES_JSON,
        KEY_FLEX_ALERT_RULES_JSON -> {
            val raw = when (key) {
                KEY_RETURN_TRIGGERS_JSON -> flexReturnTriggersJson
                KEY_FLEX_TARIFF_RULES_JSON -> flexTariffRulesJson
                else -> flexAlertRulesJson
            }
            if (raw.length > 48) "${raw.take(45)}…" else raw
        }
        else -> when (val raw = prefs.all[key]) {
            is Set<*> -> "${raw.size} items"
            null -> ""
            else -> raw.toString()
        }
    }

    private fun validateBackupEntry(key: String, value: Any) {
        when (key) {
            KEY_FLEX_CLICK_MODE -> {
                val mode = asString(value).trim().lowercase()
                if (mode != CLICK_MODE_BASIC && mode != CLICK_MODE_SMART) {
                    throw IllegalArgumentException("Modo de clic desconocido: $mode")
                }
            }
            KEY_OFFER_PICK_MODE -> {
                val mode = asString(value).trim().lowercase()
                if (mode != OFFER_PICK_FIRST && mode != OFFER_PICK_BEST) {
                    throw IllegalArgumentException("Modo de oferta desconocido: $mode")
                }
            }
            KEY_FLEX_TARIFF_MODE -> {
                val mode = asString(value).trim().lowercase()
                if (mode != TARIFF_MODE_CLASSIC && mode != TARIFF_MODE_DETAILED) {
                    throw IllegalArgumentException("Modo de tarifa desconocido: $mode")
                }
            }
            KEY_RETURN_TRIGGERS_JSON -> validateJsonArray(
                asString(value),
                "Disparadores Return",
            ) { FlexReturnScreenTrigger.fromJson(it) }
            KEY_FLEX_TARIFF_RULES_JSON -> validateJsonArray(
                asString(value),
                "Reglas de tarifas",
            ) { FlexTariffRule.fromJson(it) }
            KEY_FLEX_ALERT_RULES_JSON -> validateJsonArray(
                asString(value),
                "Reglas de alertas",
            ) { FlexAlertRule.fromJson(it) }
            KEY_MIN_HOURLY, KEY_MIN_BLOCK -> asFloat(value)
            KEY_MIN_START_HOUR -> asInt(value)
            KEY_GRAB_INTERVAL_MS, KEY_DEDUP_WINDOW_MS -> asLong(value)
            KEY_FLEX_SMART_MIN_SEC, KEY_FLEX_SMART_MAX_SEC,
            KEY_RETURN_STEP_MIN_SEC, KEY_RETURN_STEP_MAX_SEC,
            KEY_RETURN_DETECT_COOLDOWN_SEC, KEY_BURST_INTERVAL_MIN_MIN,
            KEY_BURST_INTERVAL_MAX_MIN, KEY_BURST_DURATION_MIN_SEC,
            KEY_BURST_DURATION_MAX_SEC, KEY_BURST_DURATION_SEC,
            KEY_PAUSE_OVER_CLICKS_MINUTES, KEY_ALERT_VOLUME,
            KEY_OVERLAY_X, KEY_OVERLAY_Y,
            -> asInt(value)
            KEY_BURST_CLICK_INTERVAL_MS -> asLong(value)
            else -> Unit
        }
    }

    private inline fun <T> validateJsonArray(
        raw: String,
        label: String,
        parseItem: (JSONObject) -> T,
    ) {
        if (raw.isBlank()) return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                parseItem(arr.getJSONObject(i))
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("$label: JSON inválido (${e.message ?: "formato incorrecto"})")
        }
    }

    private fun restorePrefSnapshotEntry(editor: SharedPreferences.Editor, key: String, old: Any?) {
        when (old) {
            is Boolean -> editor.putBoolean(key, old)
            is Int -> editor.putInt(key, old)
            is Long -> editor.putLong(key, old)
            is Float -> editor.putFloat(key, old)
            is String -> editor.putString(key, old)
            is Set<*> -> editor.putStringSet(key, old.mapNotNull { it?.toString() }.toSet())
            null -> Unit
            else -> editor.putString(key, old.toString())
        }
    }

    private fun restoreBackupEntry(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (key) {
            KEY_DARK_THEME -> editor.putBoolean(key, asBool(value))
            KEY_DRY_RUN -> editor.putBoolean(key, asBool(value))
            KEY_OVERLAY -> editor.putBoolean(key, asBool(value))
            KEY_OVERLAY_MOTOR_PAUSE -> editor.putBoolean(key, asBool(value))
            KEY_OVERLAY_TEST_RETURN -> editor.putBoolean(key, asBool(value))
            KEY_SHOW_CATEGORY_NAMES -> editor.putBoolean(key, asBool(value))
            KEY_MONITOR_PACKAGES -> editor.putString(key, asString(value).trim())
            KEY_MIN_HOURLY -> editor.putFloat(key, asFloat(value))
            KEY_MIN_BLOCK -> editor.putFloat(key, asFloat(value))
            KEY_MIN_START_HOUR -> editor.putInt(key, asInt(value).coerceIn(0, 23))
            KEY_CLICK_REFRESH_ENABLED -> editor.putBoolean(key, asBool(value))
            KEY_REANALYZE_AFTER_REFRESH -> editor.putBoolean(key, asBool(value))
            KEY_AUTO_SCROLL_ENABLED -> editor.putBoolean(key, asBool(value))
            KEY_OFFER_PICK_MODE -> editor.putString(key, asString(value))
            KEY_OFFER_RANK_CRITERION -> editor.putString(key, asString(value))
            KEY_DEDUP_WINDOW_MS -> editor.putLong(key, asLong(value).coerceIn(5_000L, 4 * 60 * 60 * 1000L))
            KEY_TXT_SAVE_URI -> editor.putString(key, asString(value))
            KEY_CANCEL_BAD_BLOCKS -> editor.putBoolean(key, asBool(value))
            KEY_GRAB_INTERVAL_MS -> editor.putLong(key, asLong(value).coerceIn(800L, 60_000L))
            KEY_FLEX_CLICK_MODE -> editor.putString(key, asString(value))
            KEY_FLEX_SMART_MIN_SEC -> editor.putInt(key, asInt(value).coerceIn(1, 3600))
            KEY_FLEX_SMART_MAX_SEC -> editor.putInt(key, asInt(value).coerceIn(1, 3600))
            KEY_FLEX_REFRESH_BUTTON_TEXT -> editor.putString(key, asString(value).trim())
            KEY_FLEX_CLICK_SCREEN_TEXT -> editor.putString(key, asString(value).trim())
            KEY_FLEX_CLICK_SCREEN_MODE -> editor.putString(key, normalizeMatchMode(asString(value)))
            KEY_FLEX_CLICK_SCREEN_IGNORE_CASE -> editor.putBoolean(key, asBool(value))
            KEY_FLEX_REFRESH_BUTTON_MODE -> editor.putString(key, normalizeMatchMode(asString(value)))
            KEY_FLEX_REFRESH_BUTTON_IGNORE_CASE -> editor.putBoolean(key, asBool(value))
            KEY_PAUSE_MATCH_MODE -> editor.putString(key, normalizeMatchMode(asString(value)))
            KEY_PAUSE_IGNORE_CASE -> editor.putBoolean(key, asBool(value))
            KEY_FLEX_ONLY_FOREGROUND -> editor.putBoolean(key, asBool(value))
            KEY_CONFIG_PHASE_NOTE_VISIBLE -> editor.putBoolean(key, asBool(value))
            KEY_DEBUG_LOG -> editor.putBoolean(key, asBool(value))
            KEY_FILE_LOG -> editor.putBoolean(key, asBool(value))
            KEY_AUTO_PAUSE_CAPTCHA -> editor.putBoolean(key, asBool(value))
            KEY_AUTO_PAUSE_ACCEPT -> editor.putBoolean(key, asBool(value))
            KEY_FLEX_CONTINUE_ON_TAKE_MISS -> editor.putBoolean(key, asBool(value))
            KEY_FLEX_AUTO_ACCEPT -> editor.putBoolean(key, asBool(value))
            KEY_AUTO_PAUSE_RESERVED -> editor.putBoolean(key, asBool(value))
            KEY_AUTO_RETURN_OFFERS -> editor.putBoolean(key, asBool(value))
            KEY_RETURN_STEP_MIN_SEC -> editor.putInt(key, asInt(value).coerceIn(0, 60))
            KEY_RETURN_STEP_MAX_SEC -> editor.putInt(key, asInt(value).coerceIn(0, 60))
            KEY_RETURN_DETECT_COOLDOWN_SEC -> editor.putInt(key, asInt(value).coerceIn(1, 120))
            KEY_RETURN_TRIGGERS_JSON -> editor.putString(key, asString(value))
            KEY_BURST_CLICK_ENABLED -> editor.putBoolean(key, asBool(value))
            KEY_BURST_INTERVAL_MIN_MIN -> editor.putInt(key, asInt(value).coerceIn(1, 24 * 60))
            KEY_BURST_INTERVAL_MAX_MIN -> editor.putInt(key, asInt(value).coerceIn(1, 24 * 60))
            KEY_BURST_CLICK_INTERVAL_MS -> editor.putLong(key, asLong(value).coerceIn(100L, 10_000L))
            KEY_BURST_DURATION_MIN_SEC -> editor.putInt(key, asInt(value).coerceIn(5, 3600))
            KEY_BURST_DURATION_MAX_SEC -> editor.putInt(key, asInt(value).coerceIn(5, 3600))
            KEY_BURST_DURATION_SEC -> editor.putInt(key, asInt(value).coerceIn(5, 3600))
            KEY_PAUSE_OVER_CLICKS -> editor.putBoolean(key, asBool(value))
            KEY_PAUSE_OVER_CLICKS_TEXT -> editor.putString(key, asString(value).trim())
            KEY_PAUSE_OVER_CLICKS_PAUSE_SOUND -> editor.putString(key, asString(value))
            KEY_PAUSE_OVER_CLICKS_RESUME_SOUND -> editor.putString(key, asString(value))
            KEY_OFFER_CLICK_SOUND_ENABLED -> editor.putBoolean(key, asBool(value))
            KEY_OFFER_CLICK_SOUND_URI -> editor.putString(key, asString(value))
            KEY_OFFER_CLICK_SOUND_REPEAT -> editor.putInt(key, asInt(value).coerceIn(1, 20))
            KEY_FLEX_OFFER_TAKE_DETAIL_DELAY_MS,
            KEY_CALL_ON_BLOCK_DELAY_MS,
            -> editor.putLong(KEY_CALL_ON_BLOCK_DELAY_MS, asLong(value).coerceIn(0L, 10_000L))
            KEY_FLEX_DETAIL_MISMATCH_ACTION -> {
                val mode = asString(value).trim().lowercase()
                editor.putString(
                    key,
                    if (mode == MISMATCH_ACTION_AUTO_CANCEL) {
                        MISMATCH_ACTION_AUTO_CANCEL
                    } else {
                        MISMATCH_ACTION_SOUND_STAY
                    },
                )
            }
            KEY_FLEX_DETAIL_MISMATCH_SOUND_URI -> editor.putString(key, asString(value))
            KEY_PAUSE_OVER_CLICKS_MINUTES -> editor.putInt(key, asInt(value).coerceIn(1, 24 * 60))
            KEY_OVERLAY_X -> editor.putInt(key, asInt(value))
            KEY_OVERLAY_Y -> editor.putInt(key, asInt(value))
            KEY_FLEX_TARIFF_MODE -> editor.putString(key, asString(value))
            KEY_FLEX_TARIFF_RULES_JSON -> editor.putString(key, asString(value))
            KEY_SHOW_FLEX_TARIFF_INFO -> editor.putBoolean(key, asBool(value))
            KEY_FLEX_ALERTS_ENABLED -> editor.putBoolean(key, asBool(value))
            KEY_FLEX_ALERT_RULES_JSON -> editor.putString(key, asString(value))
            KEY_ALERT_VOLUME -> editor.putInt(key, asInt(value).coerceIn(0, 100))
            KEY_VIBRATE_ON_ALERT -> editor.putBoolean(key, asBool(value))
            KEY_CALL_ON_BLOCK_ENABLED -> editor.putBoolean(key, asBool(value))
            KEY_CALL_ON_BLOCK_PHONE -> editor.putString(key, asString(value).trim())
            KEY_CALL_ON_BLOCK_WHEN_ACCEPTED -> editor.putBoolean(key, asBool(value))
            KEY_CALL_ON_BLOCK_ON_SCHEDULED -> editor.putBoolean(key, asBool(value))
            else -> writeUnknownBackupEntry(editor, key, value)
        }
    }

    private fun writeUnknownBackupEntry(editor: SharedPreferences.Editor, key: String, value: Any) {
        when {
            key.startsWith(KEY_CONFIG_SECTION_PREFIX) || key.startsWith(KEY_CONFIG_HINT_PREFIX) ->
                editor.putBoolean(key, asBool(value))
            value is Boolean -> editor.putBoolean(key, value)
            value is Int -> editor.putInt(key, value)
            value is Long -> editor.putLong(key, value)
            value is Float -> editor.putFloat(key, value)
            value is Double -> editor.putFloat(key, value.toFloat())
            value is String -> editor.putString(key, value)
            value is Set<*> -> editor.putStringSet(key, value.mapNotNull { it?.toString() }.toSet())
            else -> editor.putString(key, value.toString())
        }
    }

    private fun asBool(value: Any): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1"
        else -> false
    }

    private fun asInt(value: Any): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Float -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull() ?: throw IllegalArgumentException("Entero inválido: $value")
        else -> throw IllegalArgumentException("Entero inválido")
    }

    private fun asLong(value: Any): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is Float -> value.toLong()
        is String -> value.toLongOrNull() ?: throw IllegalArgumentException("Long inválido: $value")
        else -> throw IllegalArgumentException("Long inválido")
    }

    private fun asFloat(value: Any): Float = when (value) {
        is Float -> value
        is Double -> value.toFloat()
        is Int -> value.toFloat()
        is Long -> value.toFloat()
        is String -> value.toFloatOrNull() ?: throw IllegalArgumentException("Float inválido: $value")
        else -> throw IllegalArgumentException("Float inválido")
    }

    private fun asString(value: Any): String = when (value) {
        is String -> value
        else -> value.toString()
    }

    private fun normalizeMatchMode(raw: String?): String =
        when (raw?.trim()?.lowercase()) {
            TEXT_MATCH_EXACT, "screen_match_exact", "exact" -> TEXT_MATCH_EXACT
            TEXT_MATCH_CONTAINS, "screen_match_contains", "contains", "partial" -> TEXT_MATCH_CONTAINS
            else -> TEXT_MATCH_CONTAINS
        }

    companion object {
        const val TARIFF_MODE_CLASSIC = "classic"
        const val TARIFF_MODE_DETAILED = "detailed"
        private const val PREFS_NAME = "pichix_settings"
        private const val KEY_BOT_ENABLED = "bot_enabled"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_OVERLAY_MOTOR_PAUSE = "overlay_motor_pause_fab"
        private const val KEY_OVERLAY_TEST_RETURN = "overlay_test_return_fab"
        private const val KEY_SHOW_CATEGORY_NAMES = "show_category_names"
        private const val KEY_MONITOR_PACKAGES = "monitor_packages_csv"
        private const val KEY_MIN_HOURLY = "flex_min_hourly"
        private const val KEY_MIN_BLOCK = "flex_min_block"
        private const val KEY_MIN_START_HOUR = "flex_min_start_hour"
        private const val KEY_ONLY_REFRESH = "flex_only_refresh"
        private const val KEY_CLICK_REFRESH_ENABLED = "flex_click_refresh_enabled"
        private const val KEY_REANALYZE_AFTER_REFRESH = "flex_reanalyze_after_refresh"
        private const val KEY_DISABLE_LIST_SCROLL = "flex_disable_list_scroll"
        private const val KEY_AUTO_SCROLL_ENABLED = "flex_auto_scroll_enabled"
        private const val KEY_AUTO_SCROLL_MIGRATION = "flex_auto_scroll_migrate_v19"
        private const val KEY_OFFER_PICK_MODE = "flex_offer_pick_mode"
        private const val KEY_OFFER_RANK_CRITERION = "flex_offer_rank_criterion"
        private const val KEY_DEDUP_WINDOW_MS = "dedup_window_ms"
        private const val KEY_TXT_SAVE_URI = "txt_save_uri"
        const val OFFER_PICK_FIRST = "first"
        const val OFFER_PICK_BEST = "best"
        const val OFFER_RANK_HOURLY = "hourly"
        const val OFFER_RANK_BLOCK_PAY = "block_pay"
        const val OFFER_RANK_DURATION_MIN = "duration_min"
        const val OFFER_RANK_DURATION_MAX = "duration_max"
        const val OFFER_RANK_START_SOONEST = "start_soonest"
        private const val KEY_ONLY_REFRESH_SPLIT_MIGRATION = "flex_only_refresh_split_v18"
        private const val KEY_CANCEL_BAD_BLOCKS = "flex_cancel_bad_blocks"
        private const val KEY_GRAB_INTERVAL_MS = "flex_grab_interval_ms"
        private const val KEY_FLEX_CLICK_MODE = "flex_click_mode"
        private const val KEY_FLEX_SMART_MIN_SEC = "flex_smart_click_min_sec"
        private const val KEY_FLEX_SMART_MAX_SEC = "flex_smart_click_max_sec"
        private const val KEY_FLEX_REFRESH_BUTTON_TEXT = "flex_refresh_button_text"
        private const val KEY_FLEX_CLICK_SCREEN_TEXT = "flex_click_screen_text"
        const val CLICK_MODE_BASIC = "basic"
        const val CLICK_MODE_SMART = "smart"
        const val MISMATCH_ACTION_SOUND_STAY = "sound_stay"
        const val MISMATCH_ACTION_AUTO_CANCEL = "auto_cancel"
        const val TEXT_MATCH_CONTAINS = "contains"
        const val TEXT_MATCH_EXACT = "exact"
        private const val KEY_FLEX_CLICK_SCREEN_IGNORE_CASE = "flex_click_screen_ignore_case"
        private const val KEY_FLEX_REFRESH_BUTTON_MODE = "flex_refresh_button_mode"
        private const val KEY_FLEX_REFRESH_BUTTON_IGNORE_CASE = "flex_refresh_button_ignore_case"
        private const val KEY_PAUSE_MATCH_MODE = "pause_over_clicks_match_mode"
        private const val KEY_PAUSE_IGNORE_CASE = "pause_over_clicks_ignore_case"
        const val DEFAULT_REFRESH_BUTTON = "Refresh"
        private const val KEY_FLEX_CLICK_SCREEN_MODE = "flex_click_screen_mode"
        private const val KEY_FLEX_ONLY_FOREGROUND = "flex_only_foreground"
        private const val KEY_CONFIG_SECTION_PREFIX = "config_section_expanded_"
        private const val KEY_CONFIG_PHASE_NOTE_VISIBLE = "config_phase_note_visible"
        private const val KEY_CONFIG_HINT_PREFIX = "config_hint_visible_"
        private const val KEY_FOREGROUND_MOTOR_RESET = "flex_foreground_motor_reset_v12"
        private const val KEY_DEBUG_LOG = "debug_log"
        private const val KEY_FILE_LOG = "file_log"
        private const val KEY_AUTO_PAUSE_CAPTCHA = "auto_pause_captcha"
        private const val KEY_AUTO_PAUSE_ACCEPT = "auto_pause_accept"
        private const val KEY_FLEX_CONTINUE_ON_TAKE_MISS = "flex_continue_on_take_miss"
        private const val KEY_FLEX_AUTO_ACCEPT = "flex_auto_accept"
        private const val KEY_AUTO_PAUSE_RESERVED = "auto_pause_reserved"
        private const val KEY_AUTO_RETURN_OFFERS = "flex_auto_return_offers"
        private const val KEY_RETURN_STEP_MIN_SEC = "flex_return_step_min_sec"
        private const val KEY_RETURN_STEP_MAX_SEC = "flex_return_step_max_sec"
        private const val KEY_RETURN_DETECT_COOLDOWN_SEC = "flex_return_detect_cooldown_sec"
        private const val KEY_RETURN_TRIGGERS_JSON = "flex_return_triggers_json"
        private const val KEY_BURST_CLICK_ENABLED = "flex_burst_click_enabled"
        private const val KEY_BURST_INTERVAL_MIN_MIN = "flex_burst_interval_min_min"
        private const val KEY_BURST_INTERVAL_MAX_MIN = "flex_burst_interval_max_min"
        private const val KEY_BURST_CLICK_INTERVAL_MS = "flex_burst_click_interval_ms"
        private const val KEY_BURST_DURATION_SEC = "flex_burst_duration_sec"
        private const val KEY_BURST_DURATION_MIN_SEC = "flex_burst_duration_min_sec"
        private const val KEY_BURST_DURATION_MAX_SEC = "flex_burst_duration_max_sec"
        private const val KEY_PAUSE_OVER_CLICKS = "pause_by_over_clicks"
        private const val KEY_PAUSE_OVER_CLICKS_TEXT = "pause_by_over_clicks_text"
        private const val KEY_PAUSE_OVER_CLICKS_PAUSE_SOUND = "pause_by_over_clicks_pause_sound"
        private const val KEY_PAUSE_OVER_CLICKS_RESUME_SOUND = "pause_by_over_clicks_resume_sound"
        private const val KEY_OFFER_CLICK_SOUND_ENABLED = "offer_click_sound_enabled"
        private const val KEY_OFFER_CLICK_SOUND_URI = "offer_click_sound_uri"
        private const val KEY_OFFER_CLICK_SOUND_REPEAT = "offer_click_sound_repeat"
        private const val KEY_FLEX_OFFER_TAKE_DETAIL_DELAY_MS = "flex_offer_take_detail_delay_ms"
        private const val KEY_FLEX_DETAIL_MISMATCH_ACTION = "flex_detail_mismatch_action"
        private const val KEY_FLEX_DETAIL_MISMATCH_SOUND_URI = "flex_detail_mismatch_sound_uri"
        private const val KEY_PAUSE_OVER_CLICKS_MINUTES = "pause_by_over_clicks_minutes"
        private const val KEY_OVERLAY_X = "overlay_pos_x"
        private const val KEY_OVERLAY_Y = "overlay_pos_y"
        private const val KEY_FLEX_TARIFF_MODE = "flex_tariff_mode"
        private const val KEY_FLEX_TARIFF_RULES_JSON = "flex_tariff_rules_json"
        private const val KEY_SHOW_FLEX_TARIFF_INFO = "show_flex_tariff_mode_info"
        private const val KEY_FLEX_ALERTS_ENABLED = "flex_alerts_enabled"
        private const val KEY_FLEX_ALERT_RULES_JSON = "flex_alert_rules_json"
        private const val KEY_ALERT_VOLUME = "alert_volume"
        private const val KEY_ALERT_FORCE_VOLUME = "alert_force_volume"
        private const val KEY_VIBRATE_ON_ALERT = "vibrate_on_alert"
        private const val KEY_CALL_ON_BLOCK_ENABLED = "call_on_block_enabled"
        private const val KEY_CALL_ON_BLOCK_PHONE = "call_on_block_phone"
        private const val KEY_CALL_ON_BLOCK_WHEN_ACCEPTED = "call_on_block_when_accepted"
        private const val KEY_CALL_ON_BLOCK_ON_SCHEDULED = "call_on_block_on_scheduled"
        private const val KEY_CALL_ON_BLOCK_DELAY_MS = "call_on_block_delay_ms"

        /** No se borran al importar (estado en vivo + flags de migración). */
        val PRESERVE_ON_IMPORT: Set<String> = setOf(
            KEY_BOT_ENABLED,
            KEY_ONLY_REFRESH_SPLIT_MIGRATION,
            KEY_AUTO_SCROLL_MIGRATION,
            KEY_FOREGROUND_MOTOR_RESET,
        )

        @Volatile
        private var pendingConfigUiReload: Boolean = false

        fun markPendingConfigUiReload() {
            pendingConfigUiReload = true
        }

        fun isPendingConfigUiReload(): Boolean = pendingConfigUiReload

        fun clearPendingConfigUiReload() {
            pendingConfigUiReload = false
        }

        /** Actual en muchos dispositivos; incluye alias histórico EE.UU. */
        const val DEFAULT_FLEX_PACKAGE = "com.amazon.flex.rabbit, com.amazon.rabbit"
    }
}
