package com.oceanlab.pichix.data

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

data class BotEventEntry(
    val timestampMs: Long,
    val category: String,
    val message: String,
    val burstGroupId: Long = 0L,
) {
    fun toLine(): String {
        val msg = message.replace('\n', ' ').replace('|', '/')
        return if (burstGroupId > 0L) {
            "$timestampMs|$category|$msg|$burstGroupId"
        } else {
            "$timestampMs|$category|$msg"
        }
    }

    companion object {
        fun fromLine(raw: String): BotEventEntry? {
            val p = raw.split('|', limit = 4)
            if (p.size < 3) return null
            val ts = p[0].trim().toLongOrNull() ?: return null
            val burstId = if (p.size >= 4) p[3].trim().toLongOrNull() ?: 0L else 0L
            return BotEventEntry(ts, p[1].trim(), p[2].trim(), burstId)
        }
    }
}

/**
 * Log del bot: el motor solo encola; un hilo de fondo escribe en memoria caliente
 * y vuelca lotes antiguos a archivo cuando supera el umbral, liberando RAM.
 */
object BotEventLog {

    const val ACTION_EVENT = "com.oceanlab.pichix.BOT_EVENT_LOG"

    const val CAT_BOT = "BOT"
    const val CAT_PAUSE = "PAUSA"
    const val CAT_OFFER = "OFERTA"
    const val CAT_SCREEN = "PANTALLA"
    const val CAT_BURST = "RÁFAGA"
    const val CAT_SCROLL = "SCROLL"
    const val CAT_RETURN = "RETURN"
    const val CAT_CLICK = "CLIC"

    private const val SESSION_FILE = "pichix_bot_events.log"
    private const val HOT_MAX_EVENTS = 200
    private const val FLUSH_WHEN_ABOVE = 140
    private const val HOT_MAX_BYTES = 280_000
    private const val QUEUE_CAP = 600
    private const val MAX_FILE_BYTES = 2_000_000L
    private const val UI_DEBOUNCE_MS = 250L
    private const val FILE_READ_TAIL_LINES = 800

    private data class Pending(val ts: Long, val category: String, val message: String)

    @Volatile private var appContext: Context? = null
    private val queue = LinkedBlockingQueue<Pending>(QUEUE_CAP)
    private val workerRunning = AtomicBoolean(false)
    private val hotEvents = ArrayDeque<BotEventEntry>()
    private var hotBytes = 0
    private val storeLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val CAT_BURST_START = "__BURST_START__"
    private const val CAT_BURST_END = "__BURST_END__"
    private const val CAT_BURST_CANCEL = "__BURST_CANCEL__"

    private var burstSessionOpen = false
    private var burstStartedMs = 0L
    private var uiBroadcastScheduled = false

    fun init(context: Context) {
        appContext = context.applicationContext
        startWorkerIfNeeded()
    }

    /** No bloquea el hilo del motor: solo encola. */
    fun log(context: Context, category: String, message: String) {
        if (appContext == null) init(context)
        val item = Pending(System.currentTimeMillis(), category, message)
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
        }
        startWorkerIfNeeded()
    }

    /** Vista rápida: solo buffer caliente en RAM. */
    fun snapshot(): List<BotEventEntry> = synchronized(storeLock) {
        hotEvents.toList()
    }

    /** Vista completa (RAM + archivo volcado) para la pestaña Log; en hilo de fondo. */
    fun loadForUi(onReady: (List<BotEventEntry>) -> Unit) {
        val ctx = appContext
        if (ctx == null) {
            mainHandler.post { onReady(emptyList()) }
            return
        }
        thread(name = "BotEventLogRead") {
            val merged = buildMergedSnapshot(ctx)
            mainHandler.post { onReady(merged) }
        }
    }

    fun clear() {
        synchronized(storeLock) {
            hotEvents.clear()
            hotBytes = 0
            resetBurstSessionLocked()
        }
        queue.clear()
        appContext?.let { sessionFile(it).writeText("") }
        broadcastUiNow()
    }

    /** Elimina solo los eventos del día local actual (RAM + archivo). */
    fun clearToday() {
        synchronized(storeLock) {
            val kept = hotEvents.filterNot { isSameLocalDay(it.timestampMs) }
            hotEvents.clear()
            hotEvents.addAll(kept)
            hotBytes = kept.sumOf { it.message.length + it.category.length + 24 }
            if (burstSessionOpen && burstStartedMs > 0L && isSameLocalDay(burstStartedMs)) {
                resetBurstSessionLocked()
            }
        }
        appContext?.let { ctx ->
            val file = sessionFile(ctx)
            if (file.exists()) {
                val keptLines = file.readLines().filter { line ->
                    val entry = BotEventEntry.fromLine(line) ?: return@filter true
                    !isSameLocalDay(entry.timestampMs)
                }
                file.writeText(
                    if (keptLines.isEmpty()) "" else keptLines.joinToString("\n") + "\n",
                )
            }
        }
        broadcastUiNow()
    }

    private fun resetBurstSessionLocked() {
        burstSessionOpen = false
        burstStartedMs = 0L
    }

    private fun isSameLocalDay(ms: Long, refMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance()
        fun dayKey(t: Long): Int {
            cal.timeInMillis = t
            return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }
        return dayKey(ms) == dayKey(refMs)
    }

    /** Cierra una ráfaga abierta (p. ej. al parar el motor). */
    fun cancelOpenBurst(context: Context) {
        if (appContext == null) init(context)
        if (!queue.offer(Pending(System.currentTimeMillis(), CAT_BURST_CANCEL, ""))) {
            queue.poll()
            queue.offer(Pending(System.currentTimeMillis(), CAT_BURST_CANCEL, ""))
        }
        startWorkerIfNeeded()
    }

    fun onBurstStart(context: Context, message: String) {
        if (appContext == null) init(context)
        enqueueControl(CAT_BURST_START, message)
    }

    fun onBurstEnd(context: Context, message: String) {
        if (appContext == null) init(context)
        enqueueControl(CAT_BURST_END, message)
    }

    private fun enqueueControl(category: String, message: String) {
        if (!queue.offer(Pending(System.currentTimeMillis(), category, message))) {
            queue.poll()
            queue.offer(Pending(System.currentTimeMillis(), category, message))
        }
        startWorkerIfNeeded()
    }

    fun buildBurstSummary(entries: List<BotEventEntry>, groupId: Long): String {
        if (entries.isEmpty()) return "Ráfaga"
        val start = formatTime(groupId)
        val end = formatTime(entries.maxOf { it.timestampMs })
        val live = entries.none { it.message.contains("finalizada", ignoreCase = true) } &&
            entries.any { it.message.contains("iniciada", ignoreCase = true) }
        val suffix = when {
            live -> " · en curso"
            entries.any { it.message.contains("finalizada", ignoreCase = true) } -> ""
            else -> " · interrumpida"
        }
        return "Ráfaga $start–$end · ${entries.size} eventos$suffix"
    }

    fun formatTime(ms: Long): String = timeFmt.format(Date(ms))

    private fun startWorkerIfNeeded() {
        if (!workerRunning.compareAndSet(false, true)) return
        thread(name = "BotEventLogWorker", isDaemon = true) {
            try {
                while (true) {
                    val item = queue.take()
                    drainBatch(item)
                }
            } catch (_: InterruptedException) {
            } finally {
                workerRunning.set(false)
                if (queue.isNotEmpty()) startWorkerIfNeeded()
            }
        }
    }

    private fun drainBatch(first: Pending) {
        val batch = mutableListOf(first)
        while (batch.size < 48) {
            val next = queue.poll() ?: break
            batch.add(next)
        }
        batch.forEach { processOne(it) }
    }

    private fun processOne(pending: Pending) {
        when (pending.category) {
            CAT_BURST_START -> openBurstSession(pending)
            CAT_BURST_END -> closeBurstSession(pending)
            CAT_BURST_CANCEL -> closeBurstSession(Pending(pending.ts, CAT_BURST_END, ""))
            else -> {
                if (burstSessionOpen && isBurstEndMessage(pending)) {
                    closeBurstSession(pending)
                    return
                }
                if (isBurstStartMessage(pending)) {
                    openBurstSession(pending)
                    return
                }
                if (burstSessionOpen && shouldIncludeInBurst(pending)) {
                    addBurstEntry(pending)
                    scheduleUiBroadcast()
                    return
                }
                if (burstSessionOpen) {
                    closeBurstSession(Pending(pending.ts, CAT_BURST_END, ""))
                }
                addNormalEntry(BotEventEntry(pending.ts, pending.category, pending.message))
            }
        }
    }

    private fun shouldIncludeInBurst(pending: Pending): Boolean {
        if (pending.category == CAT_BURST) return true
        val message = pending.message
        return message.startsWith("Ráfaga") ||
            message.contains("Ráfaga →") ||
            message.contains("Ráfaga:")
    }

    private fun isBurstStartMessage(pending: Pending): Boolean =
        pending.category == CAT_BURST &&
            pending.message.contains("iniciada", ignoreCase = true)

    private fun isBurstEndMessage(pending: Pending): Boolean =
        pending.category == CAT_BURST &&
            pending.message.contains("finalizada", ignoreCase = true)

    private fun openBurstSession(pending: Pending) {
        if (burstSessionOpen) {
            closeBurstSession(Pending(pending.ts, CAT_BURST_END, ""))
        }
        burstSessionOpen = true
        burstStartedMs = pending.ts
        if (pending.message.isNotBlank()) {
            addBurstEntry(Pending(pending.ts, CAT_BURST, pending.message))
        }
        scheduleUiBroadcast()
    }

    private fun closeBurstSession(pending: Pending) {
        if (!burstSessionOpen) {
            if (pending.message.isNotBlank()) {
                addNormalEntry(BotEventEntry(pending.ts, CAT_BURST, pending.message))
            }
            return
        }
        if (pending.message.isNotBlank()) {
            addBurstEntry(Pending(pending.ts, CAT_BURST, pending.message))
        }
        burstSessionOpen = false
        burstStartedMs = 0L
        scheduleUiBroadcast()
    }

    private fun addBurstEntry(pending: Pending) {
        if (burstStartedMs <= 0L) return
        addNormalEntry(
            BotEventEntry(
                pending.ts,
                pending.category,
                pending.message,
                burstGroupId = burstStartedMs,
            ),
        )
    }

    private fun addNormalEntry(entry: BotEventEntry) {
        val toFlush = mutableListOf<BotEventEntry>()
        synchronized(storeLock) {
            hotEvents.addFirst(entry)
            hotBytes += entry.message.length + entry.category.length + 24
            while (hotEvents.size > HOT_MAX_EVENTS || hotBytes > HOT_MAX_BYTES) {
                if (hotEvents.size <= FLUSH_WHEN_ABOVE && hotBytes <= HOT_MAX_BYTES) break
                val removed = hotEvents.removeLast()
                hotBytes = max(0, hotBytes - (removed.message.length + removed.category.length + 24))
                toFlush.add(removed)
            }
        }
        if (toFlush.isNotEmpty()) {
            appendToSessionFile(toFlush.asReversed())
        }
        scheduleUiBroadcast()
    }

    private fun buildMergedSnapshot(context: Context): List<BotEventEntry> {
        val fromFile = readTailFromFile(context)
        val hot = synchronized(storeLock) { hotEvents.toList() }
        val seen = linkedSetOf<String>()
        val out = ArrayList<BotEventEntry>(fromFile.size + hot.size)
        for (e in hot) {
            val key = eventKey(e)
            if (seen.add(key)) out.add(e)
        }
        for (e in fromFile) {
            val key = eventKey(e)
            if (seen.add(key)) out.add(e)
        }
        out.sortByDescending { it.timestampMs }
        return out
    }

    private fun eventKey(e: BotEventEntry): String =
        "${e.timestampMs}|${e.category}|${e.message}|${e.burstGroupId}"

    private fun sessionFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, SESSION_FILE)
    }

    private fun appendToSessionFile(entries: List<BotEventEntry>) {
        val ctx = appContext ?: return
        if (entries.isEmpty()) return
        val file = sessionFile(ctx)
        val block = entries.joinToString("\n") { it.toLine() } + "\n"
        synchronized(storeLock) {
            file.appendText(block)
            trimFileIfNeeded(file)
        }
    }

    private fun trimFileIfNeeded(file: File) {
        if (file.length() <= MAX_FILE_BYTES) return
        val lines = file.readLines()
        val keepFrom = (lines.size / 3).coerceAtLeast(1)
        file.writeText(lines.drop(keepFrom).joinToString("\n") + "\n")
    }

    private fun readTailFromFile(context: Context): List<BotEventEntry> {
        val file = sessionFile(context)
        if (!file.exists()) return emptyList()
        val lines = file.readLines().takeLast(FILE_READ_TAIL_LINES)
        return lines.mapNotNull { BotEventEntry.fromLine(it) }
    }

    private fun scheduleUiBroadcast() {
        if (uiBroadcastScheduled) return
        uiBroadcastScheduled = true
        mainHandler.postDelayed({
            uiBroadcastScheduled = false
            val ctx = appContext ?: return@postDelayed
            LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(Intent(ACTION_EVENT))
        }, UI_DEBOUNCE_MS)
    }

    private fun broadcastUiNow() {
        val ctx = appContext ?: return
        LocalBroadcastManager.getInstance(ctx)
            .sendBroadcast(Intent(ACTION_EVENT))
    }
}
