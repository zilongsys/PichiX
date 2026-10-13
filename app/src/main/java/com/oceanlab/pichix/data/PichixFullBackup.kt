package com.oceanlab.pichix.data

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.BuildConfig
import com.oceanlab.pichix.dashboardcontrol.DashboardLink
import com.oceanlab.pichix.service.OverlayService
import com.oceanlab.pichix.service.PichixAccessibilityService
import com.oceanlab.pichix.util.PeakHoursScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporta / importa respaldo JSON: configuración, tarifas, alertas, historial CSV y logs opcionales.
 * Compatible con archivos `pichix_config_backup` (solo ajustes).
 */
object PichixFullBackup {

    const val FORMAT = "pichix_full_backup"
    const val SCHEMA_VERSION = 1
    const val MIME_TYPE = "application/json"
    private const val MAX_FILE_LOG_BYTES = 400_000
    private const val APPLIED_SUGGESTIONS_PREFS = "pichix_applied_suggestions"
    private const val APPLIED_SUGGESTIONS_KEY = "applied"

    enum class BackupKind { FULL, CONFIG_ONLY, LEGACY_TARIFFS_ONLY, INVALID }

    data class BackupCounts(
        val settingsKeys: Int = 0,
        val tariffRules: Int = 0,
        val alertRules: Int = 0,
        val offerLogLines: Int = 0,
        val fileLogs: Int = 0,
        val hasAppliedSuggestions: Boolean = false,
        val hasDashboard: Boolean = false,
    )

    data class ImportOptions(
        val restoreSettings: Boolean = true,
        val restoreTariffRules: Boolean = true,
        val restoreAlertRules: Boolean = true,
        val restoreOfferLog: Boolean = true,
        val restoreAppliedSuggestions: Boolean = true,
        val restoreDashboard: Boolean = true,
        val restoreFileLogs: Boolean = false,
        val replaceLogs: Boolean = true,
    )

    data class ImportResult(val success: Boolean, val message: String)

    data class ImportSection(
        val id: String,
        val label: String,
        var selected: Boolean,
    )

    fun exportFileName(): String {
        val day = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "pichix_backup_$day.json"
    }

    fun exportAll(context: Context): String {
        val app = context.applicationContext
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appVersionName", BuildConfig.VERSION_NAME)
        root.put("appVersionCode", BuildConfig.VERSION_CODE)
        root.put("settings", PichixConfigBackup.exportSettingsObject(app))
        root.put("flex_tariff_rules", exportTariffRules(app))
        root.put("flex_alert_rules", exportAlertRules(app))
        root.put("offer_log_csv", exportOfferLogCsv(app))
        exportAppliedSuggestions(app)?.let { root.put("applied_suggestions_json", it) }
        root.put("refresh_guard_json", com.oceanlab.pichix.service.RefreshClickGuard.exportJson(app))
        exportDashboardSettings(app)?.let { root.put("dashboard_settings", it) }
        root.put("file_logs", exportFileLogs(app))
        return root.toString(2)
    }

    fun detectKind(text: String): BackupKind {
        val root = parseRootOrNull(text) ?: return BackupKind.INVALID
        return when {
            root.optString("format") == FORMAT -> BackupKind.FULL
            root.optString("format") == "pichix_config_backup" -> BackupKind.CONFIG_ONLY
            root.optInt("schemaVersion", -1) == 0 && root.has("tariff_rules") -> BackupKind.LEGACY_TARIFFS_ONLY
            root.has("settings") -> BackupKind.CONFIG_ONLY
            else -> BackupKind.INVALID
        }
    }

    fun parseBackup(text: String): JSONObject? = parseRootOrNull(text)

    fun describeBackup(root: JSONObject): BackupCounts {
        val settings = root.optJSONObject("settings")
        val tariffs = root.optJSONArray("flex_tariff_rules")
            ?: root.optJSONArray("tariff_rules")
        val alerts = root.optJSONArray("flex_alert_rules")
            ?: root.optJSONArray("spark_alert_rules")
        val offerCsv = root.optString("offer_log_csv", "")
        val fileLogs = root.optJSONObject("file_logs")
        return BackupCounts(
            settingsKeys = settings?.length() ?: 0,
            tariffRules = tariffs?.length() ?: 0,
            alertRules = alerts?.length() ?: 0,
            offerLogLines = countCsvDataLines(offerCsv),
            fileLogs = fileLogs?.length() ?: 0,
            hasAppliedSuggestions = root.has("applied_suggestions_json") &&
                root.optString("applied_suggestions_json").isNotBlank(),
            hasDashboard = root.has("dashboard_settings") && !root.isNull("dashboard_settings"),
        )
    }

    fun isLegacyTariffOnly(root: JSONObject): Boolean =
        root.optInt("schemaVersion", -1) == 0 && root.has("tariff_rules") && !root.has("settings")

    fun listImportSections(root: JSONObject): List<ImportSection> {
        val counts = describeBackup(root)
        val legacy = isLegacyTariffOnly(root)
        val configOnly = root.optString("format") == "pichix_config_backup" ||
            (root.has("settings") && root.optString("format") != FORMAT)
        val sections = mutableListOf<ImportSection>()
        if (root.has("settings") && !root.isNull("settings") && counts.settingsKeys > 0) {
            sections.add(
                ImportSection(
                    "settings",
                    "Configuración (${counts.settingsKeys} ajustes)",
                    selected = !legacy,
                ),
            )
        }
        if (counts.tariffRules > 0) {
            sections.add(
                ImportSection(
                    "tariff_rules",
                    "Tarifas Flex (${counts.tariffRules} reglas)",
                    selected = true,
                ),
            )
        }
        if (counts.alertRules > 0) {
            sections.add(
                ImportSection(
                    "alert_rules",
                    "Alertas Flex (${counts.alertRules})",
                    selected = !legacy && !configOnly,
                ),
            )
        }
        if (counts.offerLogLines > 0 || root.has("offer_log_csv")) {
            sections.add(
                ImportSection(
                    "offer_log",
                    "Historial ofertas (${counts.offerLogLines} filas)",
                    selected = !legacy && !configOnly,
                ),
            )
        }
        if (counts.hasAppliedSuggestions) {
            sections.add(
                ImportSection(
                    "applied_suggestions",
                    "Sugerencias aplicadas (Análisis)",
                    selected = !legacy && !configOnly,
                ),
            )
        }
        if (counts.hasDashboard) {
            sections.add(
                ImportSection(
                    "dashboard",
                    "Conexión con PC (URL/token)",
                    selected = !legacy && !configOnly,
                ),
            )
        }
        if (counts.fileLogs > 0) {
            sections.add(
                ImportSection(
                    "file_logs",
                    "Logs a archivo (${counts.fileLogs} ficheros)",
                    selected = false,
                ),
            )
        }
        return sections
    }

    fun optionsFromSections(sections: List<ImportSection>, replaceLogs: Boolean): ImportOptions {
        fun on(id: String) = sections.any { it.id == id && it.selected }
        return ImportOptions(
            restoreSettings = on("settings"),
            restoreTariffRules = on("tariff_rules"),
            restoreAlertRules = on("alert_rules"),
            restoreOfferLog = on("offer_log"),
            restoreAppliedSuggestions = on("applied_suggestions"),
            restoreDashboard = on("dashboard"),
            restoreFileLogs = on("file_logs"),
            replaceLogs = replaceLogs,
        )
    }

    fun importAll(context: Context, text: String, options: ImportOptions): ImportResult {
        val root = parseBackup(text)
            ?: return ImportResult(false, "Archivo no válido (JSON corrupto)")
        return try {
            val parts = mutableListOf<String>()
            if (options.restoreTariffRules) {
                val arr = root.optJSONArray("flex_tariff_rules")
                    ?: root.optJSONArray("tariff_rules")
                if (arr != null) {
                    importTariffRules(context, arr)
                    parts.add("tarifas")
                }
            }
            if (options.restoreAlertRules) {
                val arr = root.optJSONArray("flex_alert_rules")
                    ?: root.optJSONArray("spark_alert_rules")
                if (arr != null) {
                    importAlertRules(context, arr)
                    parts.add("alertas")
                }
            }
            if (options.restoreOfferLog && root.has("offer_log_csv")) {
                importOfferLogCsv(context, root.optString("offer_log_csv"), options.replaceLogs)
                parts.add("historial")
            }
            if (options.restoreAppliedSuggestions && root.has("applied_suggestions_json")) {
                importAppliedSuggestions(context, root.optString("applied_suggestions_json"))
                parts.add("análisis")
            }
            if (options.restoreDashboard && root.has("dashboard_settings")) {
                importDashboardSettings(context, root.getJSONObject("dashboard_settings"))
                parts.add("PC")
            }
            if (options.restoreFileLogs && root.has("file_logs")) {
                importFileLogs(context, root.getJSONObject("file_logs"), options.replaceLogs)
                parts.add("logs")
            }
            if (options.restoreSettings && root.has("settings")) {
                val imp = PichixConfigBackup.importSettingsObject(
                    context,
                    root.getJSONObject("settings"),
                )
                if (imp.keysImported > 0) {
                    parts.add("configuración")
                }
                if (root.has("refresh_guard_json")) {
                    com.oceanlab.pichix.service.RefreshClickGuard.importJson(
                        context,
                        root.optString("refresh_guard_json", "[]"),
                    )
                }
            }
            if (parts.isEmpty()) {
                ImportResult(false, "Marca al menos una opción para restaurar")
            } else {
                postImport(context)
                ImportResult(true, "Restaurado: ${parts.joinToString(", ")}.")
            }
        } catch (e: Exception) {
            ImportResult(false, "Error al importar: ${e.message ?: "desconocido"}")
        }
    }

    fun postImport(context: Context) {
        val app = context.applicationContext
        try {
            PichiFileLog.setFileLogEnabled(AppSettings(app).fileLogEnabled)
            MonitorPackages.notifyReload(app)
            PeakHoursScheduler.reschedule(app)
            PichixAccessibilityService.syncEngine(app)
            OverlayService.sync(app)
            LocalBroadcastManager.getInstance(app).sendBroadcast(
                Intent(OfferLogger.ACTION_OFFER_LOGGED),
            )
            LocalBroadcastManager.getInstance(app).sendBroadcast(
                Intent(com.oceanlab.pichix.ui.MainActivity.CONFIG_IMPORTED),
            )
        } catch (_: Exception) {
        }
    }

    private fun parseRootOrNull(text: String): JSONObject? = try {
        val trimmed = text.trim().removePrefix("\uFEFF")
        when {
            trimmed.startsWith("[") -> JSONObject().apply {
                put("schemaVersion", 0)
                put("tariff_rules", JSONArray(trimmed))
            }
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun exportTariffRules(context: Context): JSONArray {
        val arr = JSONArray()
        val settings = AppSettings(context)
        FlexTariffRulesStore.load(settings).forEach { arr.put(it.toJson()) }
        return arr
    }

    private fun importTariffRules(context: Context, arr: JSONArray) {
        val rules = (0 until arr.length()).map { FlexTariffRule.fromJson(arr.getJSONObject(it)) }
        FlexTariffRulesStore.save(AppSettings(context), rules)
    }

    private fun exportAlertRules(context: Context): JSONArray {
        val arr = JSONArray()
        FlexAlertRulesStore.load(AppSettings(context)).forEach { arr.put(it.toJson()) }
        return arr
    }

    private fun importAlertRules(context: Context, arr: JSONArray) {
        val rules = (0 until arr.length()).map { FlexAlertRule.fromJson(arr.getJSONObject(it)) }
        FlexAlertRulesStore.save(AppSettings(context), rules)
    }

    private fun exportOfferLogCsv(context: Context): String {
        val lines = OfferLogCsvStore(context).readAllLines()
        return if (lines.isEmpty()) "${OfferLogCsvStore.HEADER}\n" else lines.joinToString("\n") + "\n"
    }

    private fun importOfferLogCsv(context: Context, csv: String, replace: Boolean) {
        val store = OfferLogCsvStore(context)
        if (replace) {
            val normalized = normalizeCsvWithHeader(csv, OfferLogCsvStore.HEADER)
            store.writeAllLines(normalized.lines().filter { it.isNotBlank() })
            return
        }
        val existing = store.readAllLines().toMutableList()
        val incoming = csv.lines().filter { it.isNotBlank() && !it.startsWith("timestamp,") }
        if (existing.isEmpty()) {
            store.writeAllLines(listOf(OfferLogCsvStore.HEADER) + incoming)
        } else {
            store.writeAllLines(existing + incoming)
        }
    }

    private fun exportAppliedSuggestions(context: Context): String? {
        val raw = context.getSharedPreferences(APPLIED_SUGGESTIONS_PREFS, Context.MODE_PRIVATE)
            .getString(APPLIED_SUGGESTIONS_KEY, null)
        return raw?.takeIf { it.isNotBlank() }
    }

    private fun importAppliedSuggestions(context: Context, json: String) {
        context.getSharedPreferences(APPLIED_SUGGESTIONS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(APPLIED_SUGGESTIONS_KEY, json.trim())
            .apply()
    }

    private fun exportDashboardSettings(context: Context): JSONObject? {
        val prefs = context.getSharedPreferences(DashboardLink.PREFS, Context.MODE_PRIVATE)
        if (prefs.all.isEmpty()) return null
        return JSONObject().apply {
            put("url", prefs.getString("url", "") ?: "")
            put("token", prefs.getString("token", "") ?: "")
            put("enabled", prefs.getBoolean("enabled", false))
            put("min_level", prefs.getInt("min_level", 1))
        }
    }

    private fun importDashboardSettings(context: Context, obj: JSONObject) {
        context.getSharedPreferences(DashboardLink.PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", obj.optString("url", ""))
            .putString("token", obj.optString("token", ""))
            .putBoolean("enabled", obj.optBoolean("enabled", false))
            .putInt("min_level", obj.optInt("min_level", 1))
            .apply()
    }

    private fun exportFileLogs(context: Context): JSONObject {
        val o = JSONObject()
        val dir = PichiFileLog.logsDir() ?: return o
        dir.listFiles()?.filter { file ->
            file.isFile && (
                file.name.startsWith("pichix_bot_") ||
                    file.name.startsWith("pichix_ui_") ||
                    file.name == "pichix_crash_last.txt"
                )
        }?.forEach { file ->
            o.put(
                file.name,
                if (file.length() > MAX_FILE_LOG_BYTES) readTail(file, MAX_FILE_LOG_BYTES) else file.readText(),
            )
        }
        return o
    }

    private fun importFileLogs(context: Context, obj: JSONObject, replace: Boolean) {
        val dir = PichiFileLog.logsDir() ?: return
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (!name.startsWith("pichix_")) continue
            val file = File(dir, name)
            val content = obj.optString(name, "")
            if (replace || !file.exists()) {
                file.writeText(content)
            } else {
                file.appendText("\n--- importado ---\n$content")
            }
        }
    }

    private fun normalizeCsvWithHeader(csv: String, header: String): String {
        val lines = csv.lines().filter { it.isNotBlank() }
        val data = lines.filter { line ->
            val t = line.trim()
            t != header.trim() && !t.startsWith("timestamp,")
        }
        return (listOf(header) + data).joinToString("\n") + "\n"
    }

    private fun countCsvDataLines(csv: String): Int {
        if (csv.isBlank()) return 0
        val first = csv.lineSequence().firstOrNull()?.trim().orEmpty()
        val headerLike = first.contains("timestamp") || first.contains("fecha")
        return csv.lineSequence().filter { it.isNotBlank() }.count() - if (headerLike) 1 else 0
    }

    private fun readTail(file: File, maxBytes: Int): String {
        val len = file.length()
        if (len <= maxBytes) return file.readText()
        file.inputStream().use { input ->
            input.skip(len - maxBytes)
            return input.bufferedReader().readText()
        }
    }
}
