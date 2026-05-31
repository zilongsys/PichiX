package com.oceanlab.pichix.data

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    var flexOnlyRefresh: Boolean
        get() = prefs.getBoolean(KEY_ONLY_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_ONLY_REFRESH, value).apply()

    var flexCancelBadBlocks: Boolean
        get() = prefs.getBoolean(KEY_CANCEL_BAD_BLOCKS, false)
        set(value) = prefs.edit().putBoolean(KEY_CANCEL_BAD_BLOCKS, value).apply()

    /** Intervalo del bucle Terminator-Grabber (ms). */
    var flexGrabIntervalMs: Long
        get() = prefs.getLong(KEY_GRAB_INTERVAL_MS, 2500L)
        set(value) = prefs.edit().putLong(KEY_GRAB_INTERVAL_MS, value.coerceIn(800L, 60_000L)).apply()

    var autoPauseOnCaptcha: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE_CAPTCHA, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PAUSE_CAPTCHA, value).apply()

    var autoPauseAfterAccept: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE_ACCEPT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PAUSE_ACCEPT, value).apply()

    var autoPauseOnReservedNotification: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE_RESERVED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PAUSE_RESERVED, value).apply()

    var flexAutoReturnToOffers: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RETURN_OFFERS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RETURN_OFFERS, value).apply()

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

    var vibrateOnAlert: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE_ON_ALERT, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE_ON_ALERT, value).apply()

    fun usesFlexDetailedTariff(): Boolean = flexTariffMode == TARIFF_MODE_DETAILED

    companion object {
        const val TARIFF_MODE_CLASSIC = "classic"
        const val TARIFF_MODE_DETAILED = "detailed"
        private const val PREFS_NAME = "pichix_settings"
        private const val KEY_BOT_ENABLED = "bot_enabled"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_SHOW_CATEGORY_NAMES = "show_category_names"
        private const val KEY_MONITOR_PACKAGES = "monitor_packages_csv"
        private const val KEY_MIN_HOURLY = "flex_min_hourly"
        private const val KEY_MIN_BLOCK = "flex_min_block"
        private const val KEY_MIN_START_HOUR = "flex_min_start_hour"
        private const val KEY_ONLY_REFRESH = "flex_only_refresh"
        private const val KEY_CANCEL_BAD_BLOCKS = "flex_cancel_bad_blocks"
        private const val KEY_GRAB_INTERVAL_MS = "flex_grab_interval_ms"
        private const val KEY_AUTO_PAUSE_CAPTCHA = "auto_pause_captcha"
        private const val KEY_AUTO_PAUSE_ACCEPT = "auto_pause_accept"
        private const val KEY_AUTO_PAUSE_RESERVED = "auto_pause_reserved"
        private const val KEY_AUTO_RETURN_OFFERS = "flex_auto_return_offers"
        private const val KEY_OVERLAY_X = "overlay_pos_x"
        private const val KEY_OVERLAY_Y = "overlay_pos_y"
        private const val KEY_FLEX_TARIFF_MODE = "flex_tariff_mode"
        private const val KEY_FLEX_TARIFF_RULES_JSON = "flex_tariff_rules_json"
        private const val KEY_SHOW_FLEX_TARIFF_INFO = "show_flex_tariff_mode_info"
        private const val KEY_FLEX_ALERTS_ENABLED = "flex_alerts_enabled"
        private const val KEY_FLEX_ALERT_RULES_JSON = "flex_alert_rules_json"
        private const val KEY_ALERT_VOLUME = "alert_volume"
        private const val KEY_VIBRATE_ON_ALERT = "vibrate_on_alert"

        const val DEFAULT_FLEX_PACKAGE = "com.amazon.rabbit"
    }
}
