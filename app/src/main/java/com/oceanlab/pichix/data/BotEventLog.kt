package com.oceanlab.pichix.data

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class BotEventEntry(
    val timestampMs: Long,
    val category: String,
    val message: String,
)

/** Registro en memoria + archivo para depurar el motor Flex. */
object BotEventLog {

    const val ACTION_EVENT = "com.oceanlab.pichix.BOT_EVENT_LOG"

    const val CAT_BOT = "BOT"
    const val CAT_PAUSE = "PAUSA"
    const val CAT_OFFER = "OFERTA"
    const val CAT_SCREEN = "PANTALLA"
    const val CAT_BURST = "RÁFAGA"
    const val CAT_RETURN = "RETURN"
    const val CAT_CLICK = "CLIC"

    private const val MAX_EVENTS = 600
    private val events = ArrayDeque<BotEventEntry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(context: Context, category: String, message: String) {
        val entry = BotEventEntry(System.currentTimeMillis(), category, message)
        events.addFirst(entry)
        while (events.size > MAX_EVENTS) {
            events.removeLast()
        }
        PichiFileLog.i("BotLog", "[$category] $message", always = true)
        LocalBroadcastManager.getInstance(context.applicationContext)
            .sendBroadcast(Intent(ACTION_EVENT))
    }

    @Synchronized
    fun snapshot(): List<BotEventEntry> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
    }

    fun formatTime(ms: Long): String = timeFmt.format(Date(ms))
}
