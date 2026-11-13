package com.oceanlab.pichix.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * CSV de ofertas. Todas las instancias comparten el mismo bloqueo, cola de escritura y caché
 * (hay un único fichero por proceso).
 *
 * - [appendEntry] solo encola (microsegundos, apto para el camino crítico del bot); un hilo propio
 *   escribe a disco. Cualquier lectura ([readAllLines], [readAllEntries]…) vacía antes la cola,
 *   así que siempre ve lo ya encolado.
 * - [readAllEntries] cachea el parseo y se actualiza de forma incremental al añadir entradas.
 * - Los números se escriben siempre con [Locale.US] para que el CSV no dependa del idioma del móvil.
 */
class OfferLogCsvStore(context: Context) {

    companion object {
        const val HEADER =
            "timestamp,fecha,precio,dolares_hora,duracion_horas,horario,estacion,estado,razon," +
                "first_seen_at,action_started_at,action_completed_at,reject_step1_at,reject_confirmed_at," +
                "fecha_bloque,fecha_bloque_iso"
        const val COLUMN_COUNT = 16
        const val FILE_NAME = "pichix_offers_log.csv"
        private const val TAG = "OfferLogCsvStore"

        /** Protege el fichero y la caché. */
        private val fileLock = ReentrantLock()

        /** Cola de líneas pendientes de escribir; solo se toca bajo [pendingLock] (secciones mínimas). */
        private val pendingLock = Any()
        private val pending = ArrayDeque<Pending>()

        private val writer = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pichix-csv-writer").apply { isDaemon = true }
        }

        // Caché del parseo: válida mientras el fichero tenga la misma (longitud, mtime) que cuando se cachéo.
        private var cache: List<OfferLogEntry>? = null
        private var cachePath: String? = null
        private var cacheLength = -1L
        private var cacheModified = -1L

        @Volatile private var initializedPath: String? = null

        /** Formato numérico estable (independiente del Locale del dispositivo). */
        fun formatLine(entry: OfferLogEntry): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            return "${entry.timestamp},${dateFormat.format(Date(entry.timestamp))}," +
                "${String.format(Locale.US, "%.2f", entry.price)}," +
                "${String.format(Locale.US, "%.2f", entry.hourlyRate)}," +
                "${String.format(Locale.US, "%.1f", entry.durationHours)}," +
                "${entry.timeWindow.replace(",", ";")}," +
                "${entry.station.replace(",", ";")}," +
                "${statusToCsv(entry.status)}," +
                "${entry.reason.replace(",", ";")}," +
                "${entry.firstSeenAt}," +
                "${entry.actionStartedAt}," +
                "${entry.actionCompletedAt}," +
                "${entry.rejectStep1At}," +
                "${entry.rejectConfirmedAt}," +
                "${entry.blockDate.replace(",", ";")}," +
                "${entry.blockDateIso.replace(",", ";")}"
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
                    blockDate = p.getOrNull(14)?.trim().orEmpty(),
                    blockDateIso = p.getOrNull(15)?.trim().orEmpty(),
                )
            } catch (_: Exception) {
                null
            }
        }

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

    private class Pending(val file: File, val line: String)

    private val dayStartCalendar get() = Calendar.getInstance()

    private val logFile: File = run {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, FILE_NAME).also { f -> ensureInitialized(f) }
    }

    /** Crea el fichero o corrige la cabecera una sola vez por proceso; solo lee la primera línea. */
    private fun ensureInitialized(f: File) {
        if (initializedPath == f.absolutePath) return
        fileLock.withLock {
            if (initializedPath == f.absolutePath) return
            if (!f.exists()) {
                f.writeText("$HEADER\n")
            } else {
                val first = f.bufferedReader().use { it.readLine() }
                if (first != HEADER) {
                    val lines = f.readLines()
                    f.writeText((listOf(HEADER) + lines.drop(1)).joinToString("\n") + "\n")
                    cache = null
                }
            }
            initializedPath = f.absolutePath
        }
    }

    fun getLogFilePath(): String = logFile.absolutePath

    /** Encola la línea y la escribe en segundo plano. No toca el disco en el hilo que llama. */
    fun appendEntry(entry: OfferLogEntry) {
        val p = Pending(logFile, formatLine(entry))
        synchronized(pendingLock) { pending.addLast(p) }
        writer.execute {
            try {
                fileLock.withLock { drainPendingLocked() }
            } catch (e: Throwable) {
                Log.e(TAG, "drain: ${e.message}")
            }
        }
    }

    /** Fuerza la escritura de lo pendiente (bloqueante). */
    fun flush() {
        fileLock.withLock { drainPendingLocked() }
    }

    /** Escribe lo encolado. Llamar con [fileLock] tomado. Si falla, lo pendiente se conserva para reintentar. */
    private fun drainPendingLocked() {
        val batch: List<Pending> = synchronized(pendingLock) { pending.toList() }
        if (batch.isEmpty()) return
        val files = batch.map { it.file }.distinct()
        for (f in files) {
            val before = fileStamp(f)
            val lines = batch.filter { it.file == f }.map { it.line }
            f.appendText(lines.joinToString("\n") + "\n")
            if (cache != null && cachePath == f.absolutePath && before == (cacheLength to cacheModified)) {
                // Caché al día: se le suman las entradas nuevas sin releer el fichero
                cache = cache!! + lines.mapNotNull { parseLine(it) }
                val after = fileStamp(f)
                cacheLength = after.first
                cacheModified = after.second
            }
        }
        synchronized(pendingLock) { repeat(batch.size) { pending.removeFirst() } }
    }

    private fun fileStamp(f: File): Pair<Long, Long> = f.length() to f.lastModified()

    fun readAllLines(): List<String> = fileLock.withLock {
        drainPendingLocked()
        if (!logFile.exists()) return emptyList()
        logFile.readLines()
    }

    fun writeAllLines(lines: List<String>) {
        fileLock.withLock {
            drainPendingLocked()
            logFile.writeText(lines.joinToString("\n").let { t -> if (t.endsWith("\n")) t else "$t\n" })
            cache = null
        }
    }

    /** Añade líneas ya formateadas (p. ej. importación) conservando el orden y el bloqueo. */
    fun appendLines(newLines: List<String>) {
        if (newLines.isEmpty()) return
        fileLock.withLock {
            drainPendingLocked()
            logFile.appendText(newLines.joinToString("\n") + "\n")
            cache = null
        }
    }

    fun parseLine(line: String): OfferLogEntry? = Companion.parseLine(line)

    /** Entradas de hoy (por timestamp). Usa la caché; no vuelve a parsear el fichero. */
    fun filterTodayEntries(): List<OfferLogEntry> {
        val cal = dayStartCalendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val end = cal.timeInMillis
        return readAllEntries().filter { it.timestamp in start until end }
    }

    /** Todas las entradas, cacheadas. La lista devuelta es de solo lectura: no modificarla. */
    fun readAllEntries(): List<OfferLogEntry> = fileLock.withLock {
        drainPendingLocked()
        if (!logFile.exists()) return emptyList()
        val stamp = fileStamp(logFile)
        val cached = cache
        if (cached != null && cachePath == logFile.absolutePath && stamp == (cacheLength to cacheModified)) {
            return cached
        }
        val parsed = logFile.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull { parseLine(it) }
        cache = parsed
        cachePath = logFile.absolutePath
        cacheLength = stamp.first
        cacheModified = stamp.second
        parsed
    }
}
