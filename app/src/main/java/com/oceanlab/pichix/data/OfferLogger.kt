package com.oceanlab.pichix.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OfferLogger(private val context: Context) {

    companion object {
        const val ACTION_OFFER_LOGGED = "com.oceanlab.pichix.OFFER_LOGGED"
        private const val TAG = "OfferLogger"
        private val recentSignatures = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val firstSeenSignatures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val store = OfferLogCsvStore(context)
    private val settings get() = AppSettings(context)
    private val dedupWindowMs: Long get() = settings.dedupWindowMs
    private val traceWindowMs: Long get() = maxOf(dedupWindowMs, 10 * 60 * 1000L)

    fun log(entry: OfferLogEntry) {
        val sig = signature(entry)
        val now = entry.timestamp
        val tracedEntry = entry.withTraceFirstSeen(sig, now)
        if (tracedEntry.status == OfferStatus.SEEN) {
            if (hasSeenWithinWindow(sig, now)) {
                Log.d(TAG, "Dedup VISTA: $sig")
                return
            }
            markSeenSignature(sig, now)
        }
        try {
            store.appendEntry(tracedEntry)
            broadcastLogged(tracedEntry)
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    /** Compatibilidad con llamadas antiguas del motor Flex. */
    fun log(pay: String, station: String, status: OfferStatus, note: String = "") {
        val price = FlexLogParsing.parseMoney(pay)
        log(
            OfferLogEntry(
                price = price,
                hourlyRate = 0.0,
                station = station,
                status = status,
                reason = note.ifBlank { status.name },
            ),
        )
    }

    fun hasSeenWithinWindow(sig: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = recentSignatures[sig] ?: return false
        return (now - last) < dedupWindowMs
    }

    private fun markSeenSignature(sig: String, now: Long) {
        recentSignatures[sig] = now
        val cutoff = now - dedupWindowMs
        val iter = recentSignatures.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value < cutoff) iter.remove()
        }
    }

    fun markOfferSeen(
        price: Double,
        hourlyRate: Double,
        durationHours: Double,
        timeWindow: String,
        station: String,
    ): Long = rememberFirstSeen(
        sigRaw(price, hourlyRate, durationHours, timeWindow, station),
        System.currentTimeMillis(),
    )

    private fun OfferLogEntry.withTraceFirstSeen(sig: String, now: Long): OfferLogEntry {
        if (firstSeenAt > 0L) return this
        val seenAt = rememberFirstSeen(sig, if (status == OfferStatus.SEEN) now else System.currentTimeMillis())
        return copy(firstSeenAt = seenAt)
    }

    private fun rememberFirstSeen(sig: String, now: Long): Long {
        val existing = firstSeenSignatures[sig]
        if (existing != null && now - existing < traceWindowMs) return existing
        firstSeenSignatures[sig] = now
        val cutoff = now - traceWindowMs
        val iter = firstSeenSignatures.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value < cutoff) iter.remove()
        }
        return now
    }

    private fun signature(entry: OfferLogEntry): String =
        sigRaw(entry.price, entry.hourlyRate, entry.durationHours, entry.timeWindow, entry.station)

    private fun sigRaw(
        price: Double,
        hourlyRate: Double,
        durationHours: Double,
        timeWindow: String,
        station: String,
    ): String =
        "${station.trim().lowercase()}|${"%.2f".format(price)}|${"%.2f".format(hourlyRate)}|" +
            "${"%.1f".format(durationHours)}|${timeWindow.trim()}"

    fun entriesForDisplay(entries: List<OfferLogEntry>): List<OfferLogEntry> {
        if (entries.isEmpty()) return entries
        val window = dedupWindowMs
        val sorted = entries.sortedBy { it.timestamp }
        val actionsBySig = sorted
            .filter { it.status != OfferStatus.SEEN }
            .groupBy { signature(it) }
        val seenKeptBySig = mutableMapOf<String, Long>()
        return sorted.filter { e ->
            when (e.status) {
                OfferStatus.SEEN -> {
                    val sig = signature(e)
                    val actions = actionsBySig[sig]
                    if (actions != null && actions.any { action ->
                            action.timestamp >= e.timestamp &&
                                action.timestamp - e.timestamp <= window
                        }
                    ) {
                        return@filter false
                    }
                    val lastKept = seenKeptBySig[sig]
                    if (lastKept != null && e.timestamp - lastKept < window) return@filter false
                    seenKeptBySig[sig] = e.timestamp
                    true
                }
                else -> true
            }
        }
    }

    fun getTodayEntriesForDisplay(): List<OfferLogEntry> =
        entriesForDisplay(store.filterTodayEntries())

    fun blockFurtherSeen(
        price: Double,
        hourlyRate: Double,
        durationHours: Double,
        timeWindow: String,
        station: String,
    ) {
        markSeenSignature(sigRaw(price, hourlyRate, durationHours, timeWindow, station), System.currentTimeMillis())
    }

    fun logSeenIfNew(
        price: Double,
        hourlyRate: Double,
        durationHours: Double,
        timeWindow: String,
        station: String,
    ) {
        log(
            OfferLogEntry(
                price = price,
                hourlyRate = hourlyRate,
                durationHours = durationHours,
                timeWindow = timeWindow,
                station = station,
                status = OfferStatus.SEEN,
                reason = "Vista en pantalla",
            ),
        )
    }

    fun getTodayStats(): DayStats {
        return try {
            val allEntries = getTodayEntriesForDisplay()
            val accepted = allEntries.filter { it.status == OfferStatus.ACCEPTED }
            val rejected = allEntries.filter { it.status == OfferStatus.REJECTED }
            val missed = allEntries.filter { it.status == OfferStatus.MISS }
            val simulated = allEntries.filter { it.status == OfferStatus.SIMULATED }
            val cancelled = allEntries.filter { it.status == OfferStatus.CANCELLED }
            val seenOnly = allEntries.count { it.status == OfferStatus.SEEN }
            val totalSeen = seenOnly + accepted.size + rejected.size + missed.size + simulated.size + cancelled.size
            DayStats(
                seen = totalSeen,
                accepted = accepted.size,
                rejected = rejected.size,
                miss = missed.size,
                simulated = simulated.size,
                cancelled = cancelled.size,
                avgHourly = if (accepted.isNotEmpty()) accepted.map { it.hourlyRate }.average() else 0.0,
                bestOffer = accepted.maxOfOrNull { it.price } ?: 0.0,
                totalEarned = accepted.sumOf { it.price },
                totalHours = accepted.sumOf { it.durationHours },
            )
        } catch (_: Exception) {
            DayStats()
        }
    }

    fun getRecentEntries(limit: Int = 50): List<OfferLogEntry> =
        try {
            getTodayEntriesForDisplay()
                .sortedByDescending { it.timestamp }
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }

    fun resetToday() {
        try {
            val today = dayFormat.format(Date())
            val lines = store.readAllLines()
            val header = lines.firstOrNull() ?: return
            val kept = lines.drop(1).filter { line ->
                val dateField = line.split(",", limit = 3).getOrNull(1)?.trim().orEmpty()
                !dateField.startsWith(today)
            }
            store.writeAllLines(listOf(header) + kept)
            recentSignatures.clear()
            firstSeenSignatures.clear()
            broadcastRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "resetToday: ${e.message}")
        }
    }

    fun updateEntryStatusByTimestamp(
        timestamp: Long,
        targetStatus: OfferStatus,
        logLabel: String,
    ): Boolean {
        return try {
            val lines = store.readAllLines().toMutableList()
            if (lines.isEmpty()) return false
            val idx = lines.indexOfFirst { line ->
                line.substringBefore(",").trim() == timestamp.toString()
            }
            if (idx < 0) return false
            val parts = lines[idx].split(",", limit = OfferLogCsvStore.COLUMN_COUNT).toMutableList()
            if (parts.size < 9) return false
            parts[7] = statusToCsv(targetStatus)
            lines[idx] = parts.joinToString(",")
            store.writeAllLines(lines)
            recentSignatures.clear()
            firstSeenSignatures.clear()
            broadcastRefresh()
            Log.d(TAG, "$logLabel: timestamp=$timestamp")
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateEntryStatusByTimestamp: ${e.message}")
            false
        }
    }

    private fun statusToCsv(status: OfferStatus): String = when (status) {
        OfferStatus.ACCEPTED -> "ACEPTADA"
        OfferStatus.REJECTED -> "RECHAZADA"
        OfferStatus.SIMULATED -> "SIMULADA"
        OfferStatus.SEEN -> "VISTA"
        OfferStatus.MISS -> "PERDIDA"
        OfferStatus.CANCELLED -> "CANCELADA"
    }

    fun clearHistory() {
        try {
            store.writeAllLines(listOf(OfferLogCsvStore.HEADER))
            recentSignatures.clear()
            firstSeenSignatures.clear()
            broadcastRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "clearHistory: ${e.message}")
        }
    }

    fun saveTxtToUri(treeUri: android.net.Uri, limit: Int = 200): String? {
        return try {
            val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                android.provider.DocumentsContract.getTreeDocumentId(treeUri),
            )
            val fileName = "pichix_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
            val newDocUri = android.provider.DocumentsContract.createDocument(
                context.contentResolver,
                docUri,
                "text/plain",
                fileName,
            ) ?: return null
            context.contentResolver.openOutputStream(newDocUri)?.use { os ->
                os.write(buildTxtContent(limit).toByteArray(Charsets.UTF_8))
            }
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "saveTxtToUri: ${e.message}")
            null
        }
    }

    fun buildSeparatorForScreen(context: Context): String {
        val dm = context.resources.displayMetrics
        val screenWidthDp = (dm.widthPixels / dm.density).toInt()
        val charsPerLine = (screenWidthDp / 8).coerceIn(20, 80)
        val unit = "• "
        val count = (charsPerLine / unit.length).coerceIn(4, 60)
        return unit.repeat(count)
    }

    private fun buildTxtContent(limit: Int): String {
        val sep = buildSeparatorForScreen(context)
        val entries = getRecentEntries(limit)
        val stats = getTodayStats()
        val sb = StringBuilder()
        sb.appendLine("=== PICHI X — HISTORIAL DETALLADO ===")
        sb.appendLine("Generado: ${dateFormat.format(Date())}")
        sb.appendLine()
        sb.appendLine("── RESUMEN HOY ──")
        sb.appendLine("  Vistas:      ${stats.seen}")
        sb.appendLine("  Aceptadas:   ${stats.accepted}")
        sb.appendLine("  Rechazadas:  ${stats.rejected}")
        sb.appendLine("  Canceladas:  ${stats.cancelled}")
        sb.appendLine("  Total ganado: \$${"%.2f".format(stats.totalEarned)}")
        sb.appendLine("  Mejor bloque: \$${"%.2f".format(stats.bestOffer)}")
        sb.appendLine()
        sb.appendLine("── ÚLTIMAS $limit ENTRADAS ──")
        sb.appendLine()
        entries.forEachIndexed { i, e ->
            val statusIcon = when (e.status) {
                OfferStatus.ACCEPTED -> "✅"
                OfferStatus.REJECTED -> "❌"
                OfferStatus.SIMULATED -> "🧪"
                OfferStatus.MISS -> "💨"
                OfferStatus.CANCELLED -> "🚫"
                OfferStatus.SEEN -> "👁"
            }
            sb.appendLine(sep)
            sb.appendLine("${i + 1}. $statusIcon [${dateFormat.format(Date(e.timestamp))}]")
            sb.appendLine("   Estación: ${e.station}")
            sb.appendLine("   Horario:  ${e.timeWindow.ifBlank { "—" }}")
            sb.appendLine(
                "   Precio: \$${"%.2f".format(e.price)}  |  \$/h: ${"%.2f".format(e.hourlyRate)}  |  " +
                    "Duración: ${"%.1f".format(e.durationHours)} h",
            )
            sb.appendLine("   Razón:  ${e.reason}")
            sb.appendLine()
        }
        sb.appendLine(sep)
        return sb.toString()
    }

    fun getLogFilePath(): String = store.getLogFilePath()

    private fun broadcastLogged(entry: OfferLogEntry) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(ACTION_OFFER_LOGGED).apply {
                putExtra("status", entry.status.toString())
                putExtra("price", entry.price)
            },
        )
    }

    private fun broadcastRefresh() {
        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(ACTION_OFFER_LOGGED))
    }
}

object FlexLogParsing {
    private val moneyRegex = Regex("""\$\s*([\d,]+(?:\.\d{1,2})?)""")

    fun parseMoney(text: String): Double =
        moneyRegex.find(text.replace(",", ""))?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
}
