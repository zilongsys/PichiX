package com.oceanlab.pichix.service

/**
 * Cálculos puros del contador Refresh (sin Android) para que el motor y las pruebas
 * usen las mismas reglas.
 */
object RefreshClickMath {

    const val IDLE_RESET_MS = 10 * 60_000L
    const val MAX_EPISODES = 80
    const val LEARN_FROM_LAST = 5
    const val MAX_TIMESTAMPS = 8000
    const val MIN_WINDOW_MS = 60_000L
    const val MAX_WINDOW_MS = 3 * 60 * 60_000L
    const val EPISODE_DEDUP_MS = 60_000L
    const val PERSIST_EVERY_CLICKS = 4
    const val PERSIST_EVERY_MS = 2_000L

    data class Click(val atMs: Long, val burst: Boolean)

    data class Snapshot(
        val clicks: Int,
        val elapsedMs: Long,
        val clickingMs: Long,
        val activeClickingMs: Long,
        val cycleMs: Long,
        val waitAfterPreviousMs: Long,
        val firstClickAtMs: Long,
        val lastClickAtMs: Long,
        val burstClicks: Int,
        val clicksLast60s: Int,
        val clicksLast5min: Int,
        val minGapMs: Long,
        val maxGapMs: Long,
        val avgGapMs: Long,
        val idleGaps: Int,
        val burst: Boolean,
    ) {
        val clicksPerMin: Double
            get() = pacePerMin(clicks, activeClickingMs)
        val normalClicks: Int get() = (clicks - burstClicks).coerceAtLeast(0)
    }

    enum class Decision { NONE, WARN, PAUSE }

    fun threshold(limit: Int, percent: Int): Int =
        (limit * percent.coerceIn(1, 100) / 100.0).toInt().coerceAtLeast(1)

    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** El aviso debe dispararse antes que la pausa. */
    fun orderedPercents(warnPct: Int, pausePct: Int): Pair<Int, Int> {
        val pause = pausePct.coerceIn(50, 100)
        var warn = warnPct.coerceIn(50, 99)
        if (warn >= pause) warn = (pause - 1).coerceAtLeast(50)
        return warn to pause
    }

    fun shouldDedupBanner(lastClosedAtMs: Long, nowMs: Long): Boolean =
        lastClosedAtMs > 0L && nowMs - lastClosedAtMs < EPISODE_DEDUP_MS

    fun learnedLimit(clickCounts: List<Int>): Int? {
        if (clickCounts.isEmpty()) return null
        return median(clickCounts.takeLast(LEARN_FROM_LAST).map { it.toLong() }).toInt().coerceAtLeast(1)
    }

    fun learnedWindowMs(elapsedList: List<Long>): Long? {
        if (elapsedList.isEmpty()) return null
        return (median(elapsedList.takeLast(LEARN_FROM_LAST).map { it.coerceAtLeast(1L) }) * 5 / 4)
            .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
    }

    data class CycleStats(val clicks: Int, val clickingMs: Long)

    data class Learned(
        val limit: Int?,
        val windowMs: Long?,
        val cooldownMs: Long?,
    )

    /**
     * Aviso/pausa desde el primer ciclo cerrado. Las últimas 5 detenciones afinan;
     * los ciclos cortos (Flex aún caliente) no bajan el límite si hubo uno largo.
     */
    fun learn(stats: List<CycleStats>): Learned {
        if (stats.isEmpty()) return Learned(null, null, null)
        val maxT = stats.maxOf { it.clickingMs.coerceAtLeast(1L) }
        val healthy = stats.filter { it.clickingMs >= maxT / 2 }
        val pool = if (healthy.isEmpty()) stats else healthy
        val recent = pool.takeLast(LEARN_FROM_LAST)
        return Learned(
            limit = learnedLimit(recent.map { it.clicks }),
            windowMs = learnedWindowMs(recent.map { it.clickingMs.coerceAtLeast(1L) }),
            cooldownMs = maxT.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS),
        )
    }

    /**
     * Tras un banner, espera al menos el ciclo normal (menos lo ya transcurrido
     * desde ese banner), sin bajar del mínimo que configuró el usuario.
     */
    fun resumeDelayMs(
        userMinutes: Int,
        cooldownMs: Long?,
        lastBannerAtMs: Long,
        nowMs: Long,
    ): Long {
        val userMs = userMinutes.coerceIn(1, 24 * 60) * 60_000L
        if (cooldownMs == null || cooldownMs <= 0L) return userMs
        val sinceBanner = if (lastBannerAtMs > 0L) (nowMs - lastBannerAtMs).coerceAtLeast(0L) else 0L
        val remaining = (cooldownMs - sinceBanner).coerceAtLeast(0L)
        return maxOf(userMs, remaining).coerceAtMost(MAX_WINDOW_MS)
    }

    fun decide(
        current: Int,
        limit: Int,
        warnPct: Int,
        pausePct: Int,
        warnEnabled: Boolean,
        pauseEnabled: Boolean,
        alreadyWarned: Boolean,
        alreadyPaused: Boolean,
    ): Decision {
        val (warn, pause) = orderedPercents(warnPct, pausePct)
        if (pauseEnabled && !alreadyPaused && current >= threshold(limit, pause)) return Decision.PAUSE
        if (warnEnabled && !alreadyWarned && current >= threshold(limit, warn)) return Decision.WARN
        return Decision.NONE
    }

    fun snapshot(
        clicks: List<Click>,
        nowMs: Long,
        lastBannerAtMs: Long,
        live: Boolean,
        endedAtMs: Long,
        fallbackBurst: Boolean,
    ): Snapshot? {
        if (clicks.isEmpty()) return null
        val first = clicks.first()
        val last = clicks.last()
        val times = clicks.map { it.atMs }
        val gaps = times.zipWithNext { a, b -> b - a }
        val burstClicks = clicks.count { it.burst }
        // Ritmo y «último min» se anclan al último clic, no al reloj de la pantalla.
        val clickEnd = last.atMs
        val endRef = if (live) clickEnd else endedAtMs.coerceAtLeast(clickEnd)
        val prev = lastBannerAtMs
        val wait = if (prev > 0L && first.atMs >= prev) first.atMs - prev else 0L
        val cycleStart = if (prev > 0L) prev else first.atMs
        val last60From = clickEnd - 60_000L
        val last5From = clickEnd - 5 * 60_000L
        val clickingMs = (clickEnd - first.atMs).coerceAtLeast(0L)
        val activeClickingMs = gaps.filter { it in 1 until IDLE_RESET_MS }.sum()
        return Snapshot(
            clicks = clicks.size,
            elapsedMs = (endRef - first.atMs).coerceAtLeast(0L),
            clickingMs = clickingMs,
            activeClickingMs = activeClickingMs,
            cycleMs = (endRef - cycleStart).coerceAtLeast(0L),
            waitAfterPreviousMs = wait,
            firstClickAtMs = first.atMs,
            lastClickAtMs = last.atMs,
            burstClicks = burstClicks,
            clicksLast60s = times.count { it >= last60From },
            clicksLast5min = times.count { it >= last5From },
            minGapMs = gaps.minOrNull() ?: 0L,
            maxGapMs = gaps.maxOrNull() ?: 0L,
            avgGapMs = if (gaps.isEmpty()) 0L else gaps.sum() / gaps.size,
            idleGaps = gaps.count { it >= IDLE_RESET_MS },
            burst = burstClicks > 0 || fallbackBurst,
        )
    }

    /** Clics/min solo con el tiempo entre clics (sin pausas ni tiempo mirando la pantalla). */
    fun pacePerMin(clicks: Int, activeClickingMs: Long): Double =
        if (clicks >= 2 && activeClickingMs > 0L) clicks * 60_000.0 / activeClickingMs else 0.0

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun countSince(clicks: List<Click>, fromMs: Long): Int {
        var n = 0
        for (i in clicks.indices.reversed()) {
            if (clicks[i].atMs < fromMs) break
            n++
        }
        return n
    }
}
