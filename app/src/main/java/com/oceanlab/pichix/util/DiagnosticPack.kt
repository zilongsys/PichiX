package com.oceanlab.pichix.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.oceanlab.pichix.BuildConfig
import com.oceanlab.pichix.analyzer.FlexGrabberEvaluator
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.OfferLogCsvStore
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus
import com.oceanlab.pichix.data.PichiFileLog
import com.oceanlab.pichix.data.PichixConfigBackup
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Empaqueta logs de bot/UI, historial de ofertas, config y un resumen legible
 * para compartir y diagnosticar (p. ej. tomas fuera de franja horaria).
 */
object DiagnosticPack {

    data class Result(
        val zipFile: File,
        val filesIncluded: Int,
        val summaryLineCount: Int,
    )

    fun build(context: Context): Result {
        val app = context.applicationContext
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "diagnostics").also {
            if (!it.exists()) it.mkdirs()
        }
        val workDir = File(outDir, "pack_$stamp").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        try {
            val included = mutableListOf<String>()
            val settings = AppSettings(app)

            writeText(workDir, "00_LEEME.txt", "") // placeholder; rewritten below
            included += "00_LEEME.txt"

            val summary = buildSummary(app, settings)
            writeText(workDir, "01_resumen_tomas.txt", summary.text)
            included += "01_resumen_tomas.txt"

            writeText(workDir, "02_meta_dispositivo.txt", buildDeviceMeta(app, settings))
            included += "02_meta_dispositivo.txt"

            writeText(workDir, "03_config_backup.json", PichixConfigBackup.exportToJson(app))
            included += "03_config_backup.json"

            extractTariffRulesPretty(settings)?.let { pretty ->
                writeText(workDir, "04_tarifas_reglas.json", pretty)
                included += "04_tarifas_reglas.json"
            }

            extractAlertRulesPretty(settings)?.let { pretty ->
                writeText(workDir, "05_alertas_reglas.json", pretty)
                included += "05_alertas_reglas.json"
            }

            val botCopied = copyIfPresent(PichiFileLog.botLogFileForToday(), workDir, "10_bot_log_hoy.txt")
            botCopied?.let { included += it }
            copyIfPresent(PichiFileLog.uiLogFileForToday(), workDir, "11_ui_log_hoy.txt")?.let {
                included += it
            }
            copyIfPresent(PichiFileLog.crashFile(), workDir, "12_crash_last.txt")?.let {
                included += it
            }

            val offersPath = OfferLogCsvStore(app).getLogFilePath()
            copyIfPresent(File(offersPath), workDir, "20_ofertas_log.csv")?.let {
                included += it
            }

            writeText(
                workDir,
                "00_LEEME.txt",
                buildReadme(
                    settings = settings,
                    botLogPresent = botCopied != null,
                    filesIncluded = included.filter { it != "00_LEEME.txt" },
                ),
            )

            writeText(
                workDir,
                "99_contenido.txt",
                included.filter { it != "00_LEEME.txt" }.joinToString("\n") { "- $it" } +
                    "\n- 00_LEEME.txt\n",
            )

            val zipFile = File(outDir, "pichix_diagnostico_$stamp.zip")
            zipDirectory(workDir, zipFile)
            return Result(zipFile, included.size, summary.lineCount)
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun shareIntent(context: Context, zipFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "PichiX diagnóstico ${BuildConfig.VERSION_NAME}",
            )
            putExtra(
                Intent.EXTRA_TEXT,
                "Pack de diagnóstico PichiX v${BuildConfig.VERSION_NAME} " +
                    "(logs bot/UI, ofertas CSV, config y resumen de tomas).",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private data class SummaryOut(val text: String, val lineCount: Int)

    private fun buildReadme(
        settings: AppSettings,
        botLogPresent: Boolean,
        filesIncluded: List<String>,
    ): String = buildString {
        appendLine("PichiX — Pack de diagnóstico")
        appendLine("Versión app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Generado: ${isoNow()}")
        appendLine()
        appendLine("Contenido típico:")
        appendLine("  01_resumen_tomas.txt  → tomas recientes (7 días) + madrugada 00–05")
        appendLine("  03_config_backup.json → ajustes + reglas (tarifas, alertas, etc.)")
        appendLine("  10_bot_log_hoy.txt    → log del bot (requiere «Log a archivo»)")
        appendLine("  20_ofertas_log.csv    → historial de ofertas")
        appendLine()
        appendLine("Log a archivo ahora: ${if (settings.fileLogEnabled) "ACTIVADO" else "DESACTIVADO"}")
        if (!botLogPresent) {
            appendLine()
            appendLine("AVISO: no hay 10_bot_log_hoy.txt (vacío o sin eventos).")
            appendLine("Activa Config → Log → Log a archivo, deja el bot corriendo y vuelve a exportar.")
        }
        appendLine()
        appendLine("Archivos en este ZIP (${filesIncluded.size}):")
        filesIncluded.forEach { appendLine("  - $it") }
        appendLine()
    }

    private fun buildDeviceMeta(context: Context, settings: AppSettings): String = buildString {
        appendLine("appVersion=${BuildConfig.VERSION_NAME}")
        appendLine("versionCode=${BuildConfig.VERSION_CODE}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("locale=${Locale.getDefault()}")
        appendLine("timezone=${TimeZone.getDefault().id}")
        appendLine("botEnabled=${settings.isBotEnabled}")
        appendLine("fileLogEnabled=${settings.fileLogEnabled}")
        appendLine("debugLogEnabled=${settings.debugLogEnabled}")
        appendLine("flexAutoAccept=${settings.flexAutoAccept}")
        appendLine("flexContinueOnTakeMiss=${settings.flexContinueOnTakeMiss}")
        appendLine("autoPauseAfterAccept=${settings.autoPauseAfterAccept}")
        appendLine("tariffMode=${settings.flexTariffMode}")
        appendLine("package=${context.packageName}")
        appendLine()
    }

    private fun buildSummary(context: Context, settings: AppSettings): SummaryOut {
        val store = OfferLogCsvStore(context)
        val all = store.readAllEntries()
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val recent = all.filter { it.timestamp >= cutoff }
        val today = OfferLogger(context).getTodayEntriesForDisplay()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }

        fun interesting(list: List<com.oceanlab.pichix.data.OfferLogEntry>) =
            list.filter {
                it.status == OfferStatus.ACCEPTED ||
                    it.status == OfferStatus.MISS ||
                    it.status == OfferStatus.SIMULATED ||
                    it.status == OfferStatus.CANCELLED
            }.sortedByDescending { it.timestamp }

        val interestingRecent = interesting(recent)
        val interestingToday = interesting(today)

        fun earlyMorning(list: List<com.oceanlab.pichix.data.OfferLogEntry>) =
            list.filter { entry ->
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = entry.timestamp }
                cal.get(java.util.Calendar.HOUR_OF_DAY) in 0..5
            }

        fun earlyBlockStart(list: List<com.oceanlab.pichix.data.OfferLogEntry>) =
            list.filter { e ->
                val start = FlexGrabberEvaluator.parseStartMinutesOfDay(e.timeWindow)
                start != null && start < 6 * 60
            }

        val text = buildString {
            appendLine("=== Resumen (hora local ${TimeZone.getDefault().id}) ===")
            appendLine("Modo tarifas: ${settings.flexTariffMode}")
            appendLine("Entradas hoy (UI dedup): ${today.size} | relevantes hoy: ${interestingToday.size}")
            appendLine("Entradas últimos 7 días: ${recent.size} | relevantes 7d: ${interestingRecent.size}")
            appendLine()

            val earlyTake = earlyMorning(interestingRecent)
            val earlyStart = earlyBlockStart(interestingRecent)
            appendLine("Tomas relevantes 7d entre 00:00–05:59 (hora del móvil): ${earlyTake.size}")
            appendLine("Tomas relevantes 7d con inicio de bloque < 06:00: ${earlyStart.size}")
            appendLine()

            if (earlyStart.isNotEmpty()) {
                appendLine("--- Bloques con inicio de madrugada (horario del bloque) ---")
                earlyStart.take(40).forEach { e -> appendLine(formatEntry(fmt, e)) }
                appendLine()
            }
            if (earlyTake.isNotEmpty()) {
                appendLine("--- Acciones entre 00:00–05:59 (hora del móvil) ---")
                earlyTake.take(40).forEach { e -> appendLine(formatEntry(fmt, e)) }
                appendLine()
            }

            appendLine("--- Últimas 60 tomas relevantes (7 días) ---")
            if (interestingRecent.isEmpty()) {
                appendLine("(sin aceptadas/perdidas/simuladas/canceladas en 7 días)")
            } else {
                interestingRecent.take(60).forEach { e -> appendLine(formatEntry(fmt, e)) }
            }
            appendLine()
            appendLine("--- SEEN recientes 7d (muestra, máx 30) ---")
            recent.filter { it.status == OfferStatus.SEEN }
                .sortedByDescending { it.timestamp }
                .take(30)
                .forEach { e -> appendLine(formatEntry(fmt, e)) }
            appendLine()
        }
        return SummaryOut(text, interestingRecent.size)
    }

    private fun formatEntry(fmt: SimpleDateFormat, e: com.oceanlab.pichix.data.OfferLogEntry): String {
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = e.timestamp }
            .get(java.util.Calendar.HOUR_OF_DAY)
        return "${fmt.format(Date(e.timestamp))} | h=$hour | ${e.status.name} | " +
            "${e.station} | $${"%.2f".format(e.price)} | $${"%.2f".format(e.hourlyRate)}/h | " +
            "${e.timeWindow} | ${e.blockDate} | ${e.reason.take(140)}"
    }

    private fun extractTariffRulesPretty(settings: AppSettings): String? {
        val raw = settings.flexTariffRulesJson
        if (raw.isBlank()) return null
        return try {
            org.json.JSONArray(raw).toString(2)
        } catch (_: Exception) {
            raw
        }
    }

    private fun extractAlertRulesPretty(settings: AppSettings): String? {
        val raw = settings.flexAlertRulesJson
        if (raw.isBlank()) return null
        return try {
            org.json.JSONArray(raw).toString(2)
        } catch (_: Exception) {
            raw
        }
    }

    private fun writeText(dir: File, name: String, content: String) {
        File(dir, name).writeText(content)
    }

    private fun copyIfPresent(src: File?, dir: File, name: String): String? {
        if (src == null || !src.exists() || src.length() <= 0L) return null
        src.copyTo(File(dir, name), overwrite = true)
        return name
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            sourceDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                FileInputStream(file).use { fis ->
                    zos.putNextEntry(ZipEntry(file.name))
                    fis.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
}
