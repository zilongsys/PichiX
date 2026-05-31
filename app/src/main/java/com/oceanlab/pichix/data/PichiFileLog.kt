package com.oceanlab.pichix.data

import android.content.Context
import android.util.Log
import com.oceanlab.pichix.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object PichiFileLog {

    enum class Channel { BOT, UI }

    private const val TAG = "PichiFileLog"
    private const val CRASH_FILE = "pichix_crash_last.txt"
    private const val MAX_BYTES_PER_FILE = 1_500_000L
    private const val KEEP_DAYS = 3
    private const val QUEUE_CAP = 800

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile private var appContext: Context? = null
    @Volatile private var fileLogEnabled = false
    private val queue = LinkedBlockingQueue<LogLine>(QUEUE_CAP)
    private val writerRunning = AtomicBoolean(false)
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    private data class LogLine(val channel: Channel, val tag: String, val level: String, val msg: String)

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        fileLogEnabled = AppSettings(app).fileLogEnabled
        installCrashHandlerOnce()
        i(TAG, "init fileLog=$fileLogEnabled dir=${logsDir()?.absolutePath}", Channel.BOT, always = true)
        startWriterIfNeeded()
    }

    fun setFileLogEnabled(enabled: Boolean) {
        fileLogEnabled = enabled
    }

    fun logsDir(): File? {
        val ctx = appContext ?: return null
        return ctx.getExternalFilesDir(null) ?: ctx.filesDir
    }

    fun botLogFileForToday(): File? = logFile(Channel.BOT, dayFmt.format(Date()))
    fun uiLogFileForToday(): File? = logFile(Channel.UI, dayFmt.format(Date()))
    fun crashFile(): File? = logsDir()?.let { File(it, CRASH_FILE) }

    fun ui(tag: String, msg: String) = i(tag, msg, Channel.UI)

    fun e(tag: String, msg: String, t: Throwable, channel: Channel = Channel.BOT, force: Boolean = false) {
        Log.e(tag, msg, t)
        enqueue(channel, tag, "E", "$msg\n${throwableString(t)}", force = force)
    }

    fun i(tag: String, msg: String, channel: Channel = Channel.BOT, always: Boolean = false) {
        Log.i(tag, msg)
        if (always) enqueue(channel, tag, "I", msg, force = true)
        else enqueue(channel, tag, "I", msg)
    }

    private fun enqueue(channel: Channel, tag: String, level: String, msg: String, force: Boolean = false) {
        if (!force && !fileLogEnabled) return
        val line = LogLine(channel, tag, level, msg)
        if (!queue.offer(line)) {
            queue.poll()
            queue.offer(line)
        }
        startWriterIfNeeded()
    }

    private fun startWriterIfNeeded() {
        if (!writerRunning.compareAndSet(false, true)) return
        thread(name = "PichiFileLogWriter", isDaemon = true) {
            try {
                while (true) {
                    val first = queue.take()
                    drainToBatch(first)
                }
            } catch (_: InterruptedException) {
            } finally {
                writerRunning.set(false)
                if (queue.isNotEmpty()) startWriterIfNeeded()
            }
        }
    }

    private fun drainToBatch(first: LogLine) {
        val batch = mutableListOf(first)
        while (batch.size < 64) {
            val next = queue.poll() ?: break
            batch.add(next)
        }
        try {
            batch.forEach { writeLine(it) }
            purgeOldLogs()
        } catch (e: Exception) {
            Log.e(TAG, "write batch: ${e.message}")
        }
    }

    private fun writeLine(line: LogLine) {
        val file = logFile(line.channel, dayFmt.format(Date())) ?: return
        val text = "${tsFmt.format(Date())} [${line.level}] ${line.tag}: ${line.msg}\n"
        synchronized(this) {
            file.appendText(text)
            trimIfNeeded(file)
        }
    }

    private fun logFile(channel: Channel, day: String): File? {
        val dir = logsDir() ?: return null
        val prefix = if (channel == Channel.BOT) "pichix_bot_" else "pichix_ui_"
        return File(dir, "$prefix$day.log")
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES_PER_FILE) return
        val lines = file.readLines()
        val keepFrom = (lines.size / 3).coerceAtLeast(1)
        file.writeText(lines.drop(keepFrom).joinToString("\n") + "\n")
    }

    private fun purgeOldLogs() {
        val dir = logsDir() ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (!f.name.startsWith("pichix_bot_") && !f.name.startsWith("pichix_ui_")) return@forEach
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    private fun installCrashHandlerOnce() {
        if (defaultHandler != null) return
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                writeCrashReport(t.name, e)
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(t, e)
        }
    }

    private fun writeCrashReport(threadName: String, e: Throwable) {
        val file = crashFile() ?: return
        val body = buildString {
            appendLine("=== PichiX crash ${tsFmt.format(Date())} ===")
            appendLine("thread=$threadName")
            appendLine(throwableString(e))
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }
        synchronized(this) {
            file.writeText(body)
        }
        enqueue(Channel.BOT, TAG, "E", "CRASH thread=$threadName\n${throwableString(e)}", force = true)
    }

    private fun throwableString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().take(12_000)
    }
}
