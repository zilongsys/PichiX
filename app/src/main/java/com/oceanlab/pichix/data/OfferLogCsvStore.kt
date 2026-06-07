package com.oceanlab.pichix.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class OfferLogCsvStore(context: Context) {

    companion object {
        const val HEADER =
            "timestamp,fecha,precio,dolares_hora,duracion_horas,horario,estacion,estado,razon," +
                "first_seen_at,action_started_at,action_completed_at,reject_step1_at,reject_confirmed_at"
        const val COLUMN_COUNT = 14
        const val FILE_NAME = "pichix_offers_log.csv"
    }

    private val lock = ReentrantLock()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val logFile: File = run {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, FILE_NAME).also { f ->
            lock.withLock {
                if (!f.exists()) {
                    f.writeText("$HEADER\n")
                } else {
                    val lines = f.readLines()
                    if (lines.firstOrNull() != HEADER) {
                        f.writeText((listOf(HEADER) + lines.drop(1)).joinToString("\n") + "\n")
                    }
                }
            }
        }
    }

    fun getLogFilePath(): String = logFile.absolutePath

    fun appendEntry(entry: OfferLogEntry) {
        val line = "${entry.timestamp},${dateFormat.format(Date(entry.timestamp))}," +
            "${"%.2f".format(entry.price)}," +
            "${"%.2f".format(entry.hourlyRate)}," +
            "${"%.1f".format(entry.durationHours)}," +
            "${entry.timeWindow.replace(",", ";")}," +
            "${entry.station.replace(",", ";")}," +
            "${statusToCsv(entry.status)}," +
            "${entry.reason.replace(",", ";")}," +
            "${entry.firstSeenAt}," +
            "${entry.actionStartedAt}," +
            "${entry.actionCompletedAt}," +
            "${entry.rejectStep1At}," +
            "${entry.rejectConfirmedAt}\n"
        lock.withLock { logFile.appendText(line) }
    }

    fun readAllLines(): List<String> = lock.withLock {
        if (!logFile.exists()) return emptyList()
        logFile.readLines()
    }

    fun writeAllLines(lines: List<String>) {
        lock.withLock {
            logFile.writeText(lines.joinToString("\n").let { t -> if (t.endsWith("\n")) t else "$t\n" })
        }
    }

    fun parseLine(line: String): OfferLogEntry? {
        return try {
            val p = line.split(",", limit = COLUMN_COUNT)
            if (p.size < 9) return null
            OfferLogEntry(
                timestamp = p[0].trim().toLong(),
                price = p[2].trim().toDouble(),
                hourlyRate = p[3].trim().toDouble(),
                durationHours = p[4].trim().toDoubleOrNull() ?: 0.0,
                timeWindow = p[5].trim(),
                station = p[6].trim(),
                status = csvToStatus(p[7].trim()),
                reason = p.getOrElse(8) { "" }.trim(),
                firstSeenAt = p.getOrNull(9).toLongOrZero(),
                actionStartedAt = p.getOrNull(10).toLongOrZero(),
                actionCompletedAt = p.getOrNull(11).toLongOrZero(),
                rejectStep1At = p.getOrNull(12).toLongOrZero(),
                rejectConfirmedAt = p.getOrNull(13).toLongOrZero(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun filterTodayEntries(): List<OfferLogEntry> {
        val today = dayFormat.format(Date())
        return readAllLines().drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val entry = parseLine(line) ?: return@mapNotNull null
                val dateField = line.split(",", limit = 3).getOrNull(1)?.trim().orEmpty()
                if (dateField.startsWith(today)) entry else null
            }
    }

    fun readAllEntries(): List<OfferLogEntry> =
        readAllLines().drop(1).filter { it.isNotBlank() }.mapNotNull { parseLine(it) }

    private fun String?.toLongOrZero(): Long = this?.trim()?.toLongOrNull() ?: 0L

    private fun statusToCsv(status: OfferStatus): String = when (status) {
        OfferStatus.ACCEPTED -> "ACEPTADA"
        OfferStatus.REJECTED -> "RECHAZADA"
        OfferStatus.SIMULATED -> "SIMULADA"
        OfferStatus.SEEN -> "VISTA"
        OfferStatus.MISS -> "PERDIDA"
        OfferStatus.CANCELLED -> "CANCELADA"
    }

    private fun csvToStatus(raw: String): OfferStatus = when (raw) {
        "ACEPTADA", "SI" -> OfferStatus.ACCEPTED
        "RECHAZADA" -> OfferStatus.REJECTED
        "SIMULADA" -> OfferStatus.SIMULATED
        "PERDIDA" -> OfferStatus.MISS
        "CANCELADA" -> OfferStatus.CANCELLED
        "VISTA" -> OfferStatus.SEEN
        else -> OfferStatus.SEEN
    }
}
