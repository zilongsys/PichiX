package com.oceanlab.pichix.data

import android.content.Context
import com.oceanlab.pichix.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Exporta / importa todas las preferencias de [AppSettings] (SharedPreferences `pichix_settings`). */
object PichixConfigBackup {

    private const val FORMAT = "pichix_config_backup"
    private const val FORMAT_VERSION = 2

    data class SkippedEntry(
        val key: String,
        val reason: String,
    )

    data class ImportedEntry(
        val key: String,
        val displayValue: String,
    )

    data class ImportResult(
        val imported: List<ImportedEntry>,
        val skipped: List<SkippedEntry>,
        val clearedLocalKeys: Int,
        val sourceAppVersion: String?,
        val sourceExportedAt: String?,
    ) {
        val keysImported: Int get() = imported.size
        val keysSkipped: Int get() = skipped.size
    }

    data class ExportResult(
        val keysExported: Int,
        val json: String,
    )

    fun export(context: Context): ExportResult {
        val json = exportToJson(context)
        val count = JSONObject(json).getJSONObject("settings").length()
        return ExportResult(count, json)
    }

    fun exportToJson(context: Context): String {
        val settings = AppSettings(context.applicationContext)
        val entries = JSONObject()
        settings.snapshotForBackup().forEach { (key, value) ->
            entries.put(key, encodeValue(value))
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("exportedAt", Instant.now().toString())
            put("settings", entries)
        }.toString(2)
    }

    fun importFromJson(context: Context, json: String): ImportResult {
        val root = parseRoot(json)
        val settingsObj = root.getJSONObject("settings")
        val skipped = mutableListOf<SkippedEntry>()
        val decoded = linkedMapOf<String, Any>()

        val keys = settingsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in AppSettings.PRESERVE_ON_IMPORT) {
                skipped.add(
                    SkippedEntry(
                        key,
                        if (key == "bot_enabled") {
                            "Estado del bot (no se restaura al importar)"
                        } else {
                            "Clave interna (no se restaura)"
                        },
                    ),
                )
                continue
            }
            try {
                val raw = settingsObj.get(key)
                if (raw == null || raw === JSONObject.NULL) {
                    skipped.add(SkippedEntry(key, "Valor vacío"))
                    continue
                }
                decoded[key] = decodeValue(raw)
            } catch (e: Exception) {
                skipped.add(SkippedEntry(key, e.message ?: "Error al leer valor"))
            }
        }

        if (decoded.isEmpty()) {
            throw IllegalArgumentException(
                "No se importó ningún ajuste (${skipped.size} omitidos). Revisa el formato del archivo.",
            )
        }

        val settings = AppSettings(context.applicationContext)
        val restoreResult = settings.restoreFromBackup(decoded)
        val applySkipped = restoreResult.skipped.map { SkippedEntry(it.key, it.reason) }
        val allSkipped = (skipped + applySkipped).sortedBy { it.key }

        val after = AppSettings(context.applicationContext)
        val imported = restoreResult.appliedKeys.map { key ->
            ImportedEntry(key, after.readBackupDisplayValue(key))
        }

        return ImportResult(
            imported = imported,
            skipped = allSkipped,
            clearedLocalKeys = restoreResult.clearedLocalKeys,
            sourceAppVersion = root.optString("appVersion").ifBlank { null },
            sourceExportedAt = root.optString("exportedAt").ifBlank { null },
        )
    }

    fun formatImportReport(result: ImportResult): String = buildString {
        if (!result.sourceAppVersion.isNullOrBlank() || !result.sourceExportedAt.isNullOrBlank()) {
            append("Origen: ")
            if (!result.sourceAppVersion.isNullOrBlank()) append("v${result.sourceAppVersion}")
            if (!result.sourceExportedAt.isNullOrBlank()) {
                if (!result.sourceAppVersion.isNullOrBlank()) append(" · ")
                append(result.sourceExportedAt)
            }
            append('\n')
        }
        append("Ajustes reemplazados (excepto bot activo/inactivo).\n\n")
        append("IMPORTADOS (${result.keysImported})\n")
        result.imported.forEach { entry ->
            append("  ✓ ")
            append(labelFor(entry.key))
            if (entry.displayValue.isNotBlank()) {
                append(" = ")
                append(entry.displayValue)
            }
            append('\n')
        }
        append('\n')
        append("OMITIDOS (${result.keysSkipped})\n")
        if (result.skipped.isEmpty()) {
            append("  — ninguno\n")
        } else {
            append("  (no importados; se conservó el valor local si existía)\n")
            result.skipped.forEach { entry ->
                append("  ✗ ")
                append(labelFor(entry.key))
                append(" — ")
                append(entry.reason)
                append('\n')
            }
        }
    }

    fun readText(context: Context, uri: android.net.Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalArgumentException("No se pudo leer el archivo.")
    }

    fun writeText(context: Context, uri: android.net.Uri, text: String) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw IllegalArgumentException("No se pudo escribir el archivo.")
    }

    fun suggestedExportFileName(): String =
        "pichix_config_${BuildConfig.VERSION_NAME}_${System.currentTimeMillis()}.json"

    private fun parseRoot(json: String): JSONObject {
        val trimmed = json.trim().removePrefix("\uFEFF")
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("El archivo está vacío.")
        }
        val root = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON inválido: ${e.message ?: "formato incorrecto"}")
        }
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("El archivo no es un respaldo de configuración PichiX.")
        }
        val version = root.optInt("formatVersion", 0)
        if (version < 1 || version > FORMAT_VERSION) {
            throw IllegalArgumentException("Versión de respaldo no compatible (v$version).")
        }
        if (!root.has("settings") || root.optJSONObject("settings") == null) {
            throw IllegalArgumentException("El respaldo no contiene ajustes.")
        }
        return root
    }

    private fun decodeValue(raw: Any): Any = when (raw) {
        is JSONObject -> {
            if (raw.has("t") && raw.has("v")) {
                decodeTypedValue(raw)
            } else {
                throw IllegalArgumentException("Objeto sin tipo {t,v}")
            }
        }
        is Boolean -> raw
        is Int -> raw
        is Long -> raw
        is Double -> raw
        is Float -> raw
        is String -> raw
        is JSONArray -> decodeStringSet(raw)
        else -> throw IllegalArgumentException("Tipo no soportado: ${raw.javaClass.simpleName}")
    }

    private fun decodeTypedValue(entry: JSONObject): Any {
        val raw = entry.get("v")
        return when (entry.optString("t")) {
            "bool" -> entry.getBoolean("v")
            "int" -> when (raw) {
                is Int -> raw
                is Long -> raw.toInt()
                is Double -> raw.toInt()
                is String -> raw.toIntOrNull() ?: throw IllegalArgumentException("Entero inválido")
                else -> entry.getInt("v")
            }
            "long" -> when (raw) {
                is Long -> raw
                is Int -> raw.toLong()
                is Double -> raw.toLong()
                is String -> raw.toLongOrNull() ?: throw IllegalArgumentException("Long inválido")
                else -> entry.getLong("v")
            }
            "float" -> when (raw) {
                is Double -> raw.toFloat()
                is Float -> raw
                is Int -> raw.toFloat()
                is Long -> raw.toFloat()
                is String -> raw.toFloatOrNull() ?: throw IllegalArgumentException("Float inválido")
                else -> entry.getDouble("v").toFloat()
            }
            "str" -> entry.optString("v")
            "set" -> decodeStringSet(entry.getJSONArray("v"))
            else -> throw IllegalArgumentException("Tipo desconocido: ${entry.optString("t")}")
        }
    }

    private fun decodeStringSet(arr: JSONArray): Set<String> {
        val set = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            set.add(arr.optString(i))
        }
        return set
    }

    private fun encodeValue(value: Any?): JSONObject {
        val entry = JSONObject()
        when (value) {
            is Boolean -> {
                entry.put("t", "bool")
                entry.put("v", value)
            }
            is Int -> {
                entry.put("t", "int")
                entry.put("v", value)
            }
            is Long -> {
                entry.put("t", "long")
                entry.put("v", value)
            }
            is Float -> {
                entry.put("t", "float")
                entry.put("v", value.toDouble())
            }
            is Double -> {
                entry.put("t", "float")
                entry.put("v", value)
            }
            is String -> {
                entry.put("t", "str")
                entry.put("v", value)
            }
            is Set<*> -> {
                val arr = JSONArray()
                value.forEach { item -> arr.put(item?.toString().orEmpty()) }
                entry.put("t", "set")
                entry.put("v", arr)
            }
            null -> {
                entry.put("t", "str")
                entry.put("v", "")
            }
            else -> {
                entry.put("t", "str")
                entry.put("v", value.toString())
            }
        }
        return entry
    }

    private fun labelFor(key: String): String {
        val human = KEY_LABELS[key]
        return if (human != null) "$human ($key)" else key
    }

    private val KEY_LABELS = mapOf(
        "flex_auto_accept" to "Aceptar automáticamente",
        "dry_run" to "Modo simulación",
        "flex_auto_return_offers" to "Return 2 offers",
        "flex_return_step_min_sec" to "Return paso min (seg)",
        "flex_return_step_max_sec" to "Return paso max (seg)",
        "flex_return_detect_cooldown_sec" to "Return cooldown (seg)",
        "flex_return_triggers_json" to "Disparadores Return",
        "overlay_enabled" to "Botón flotante",
        "flex_min_hourly" to "Mín. \$/h",
        "flex_min_block" to "Mín. bloque",
        "flex_tariff_rules_json" to "Reglas tarifas",
        "flex_alert_rules_json" to "Reglas alertas",
        "monitor_packages_csv" to "Paquete Amazon",
        "dark_theme" to "Tema oscuro",
        "flex_smart_click_min_sec" to "Smart click min (seg)",
        "flex_smart_click_max_sec" to "Smart click max (seg)",
        "flex_grab_interval_ms" to "Basic click intervalo (ms)",
        "pause_by_over_clicks_minutes" to "Pausa over-clicks (min)",
        "call_on_block_enabled" to "Llamar al tomar bloque",
        "call_on_block_phone" to "Número llamada bloque",
    )
}
