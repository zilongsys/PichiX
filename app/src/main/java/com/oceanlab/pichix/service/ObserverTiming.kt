package com.oceanlab.pichix.service

import android.content.Context
import com.oceanlab.pichix.data.BotEventLog

/**
 * Mide cuánto tarda cada ciclo del observador (lectura de pantalla + decisión) en el hilo principal,
 * para decidir con datos si hace falta moverlo a un hilo propio. Solo cuentan los ciclos con trabajo
 * real (≥ [MIN_COUNTED_NS]); si uno supera [SLOW_NS] avisa en el log del bot, como máximo una vez por minuto.
 */
object ObserverTiming {

    private const val MIN_COUNTED_NS = 2_000_000L      // 2 ms
    private const val SLOW_NS = 150_000_000L           // 150 ms
    private const val WARN_EVERY_MS = 60_000L

    /** Momento del último ciclo del observador (0 = ninguno aún). Lo muestra Inicio. */
    @Volatile var lastCycleAtMs: Long = 0L
        private set

    private var count = 0L
    private var emaMs = 0.0
    private var maxMs = 0.0
    private var lastWarnAt = 0L

    @Synchronized
    fun record(context: Context, nanos: Long) {
        lastCycleAtMs = System.currentTimeMillis()
        if (nanos < MIN_COUNTED_NS) return
        val ms = nanos / 1_000_000.0
        count++
        emaMs = if (count == 1L) ms else emaMs * 0.9 + ms * 0.1
        if (ms > maxMs) maxMs = ms
        val now = System.currentTimeMillis()
        if (nanos >= SLOW_NS && now - lastWarnAt >= WARN_EVERY_MS) {
            lastWarnAt = now
            BotEventLog.log(
                context,
                BotEventLog.CAT_BOT,
                "Ciclo lento del observador: ${"%.0f".format(java.util.Locale.US, ms)} ms " +
                    "(media ${"%.0f".format(java.util.Locale.US, emaMs)} ms, máx ${"%.0f".format(java.util.Locale.US, maxMs)} ms, $count ciclos)",
            )
        }
    }

    @Synchronized
    fun summary(): String =
        if (count == 0L) "sin ciclos medidos"
        else "ciclos=$count media=${"%.1f".format(java.util.Locale.US, emaMs)}ms máx=${"%.1f".format(java.util.Locale.US, maxMs)}ms"
}
