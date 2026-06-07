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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

data class BotEventEntry(
    val timestampMs: Long,
    val category: String,
    val message: String,
) {
    fun toLine(): String = "$timestampMs|$category|${message.replace('\n', ' ').replace('|', '/')}"

    companion object {
        fun fromLine(raw: String): BotEventEntry? {
            val p = raw.split('|', limit = 3)
            if (p.size < 3) return null
            val ts = p[0].trim().toLongOrNull() ?: return null
            return BotEventEntry(ts, p[1].trim(), p[2].trim())
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
        }
        queue.clear()
        appContext?.let { sessionFile(it).writeText("") }
        broadcastUiNow()
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
        val entry = BotEventEntry(pending.ts, pending.category, pending.message)
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

    private fun eventKey(e: BotEventEntry): String = "${e.timestampMs}|${e.category}|${e.message}"

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
