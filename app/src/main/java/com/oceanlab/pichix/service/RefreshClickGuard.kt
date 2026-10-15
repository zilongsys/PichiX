package com.oceanlab.pichix.service

import android.content.Context
import com.oceanlab.pichix.data.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cuenta clics Refresh y aprende, de cada banner «demasiados toques», cuántos clics
 * y cuánto tiempo hicieron falta. Un **ciclo** va desde un banner hasta el siguiente.
 * Avisa / pausa antes de volver a llegar a ese límite.
 */
object RefreshClickGuard {

    enum class Decision { NONE, WARN, PAUSE }

    /**
     * Ciclo cerrado: del banner anterior (o del primer clic, si es el primero)
     * hasta el banner «demasiados toques» que lo cierra.
     */
    data class Episode(
        val atMs: Long,
        val clicks: Int,
        val elapsedMs: Long,
        val clicksLast60s: Int,
        val burst: Boolean,
        val previousBannerAtMs: Long = 0L,
        val firstClickAtMs: Long = 0L,
        val lastClickAtMs: Long = 0L,
        val burstClicks: Int = 0,
        val clicksLast5min: Int = 0,
        val minGapMs: Long = 0L,
        val maxGapMs: Long = 0L,
        val avgGapMs: Long = 0L,
        val idleGaps: Int = 0,
        val warned: Boolean = false,
        val pausedPreventively: Boolean = false,
        val waitAfterPreviousMs: Long = 0L,
        val cycleMs: Long = 0L,
    ) {
        val clicksPerMin: Double
            get() = if (elapsedMs > 0) clicks * 60_000.0 / elapsedMs else 0.0
        val clickingMs: Long
            get() = if (firstClickAtMs > 0L && lastClickAtMs >= firstClickAtMs) {
                lastClickAtMs - firstClickAtMs
            } else {
                elapsedMs
            }
        val normalClicks: Int get() = (clicks - burstClicks).coerceAtLeast(0)
        val isLive: Boolean get() = atMs <= 0L
    }

    data class Status(
        val sessionClicks: Int,
        val windowClicks: Int,
        val learnedLimit: Int?,
        val windowMs: Long?,
        val episodes: List<Episode>,
        val live: Episode? = null,
        val lastBannerAtMs: Long = 0L,
    )

    private const val PREFS = "pichix_refresh_guard"
    private const val KEY_EPISODES = "episodes"
    private const val KEY_LAST_BANNER = "last_banner_at"
    private const val MAX_EPISODES = 80
    private const val LEARN_FROM_LAST = 5
    private const val MAX_TIMESTAMPS = 8000
    /** Sin clics durante este tiempo = Flex ya «olvidó» la racha preventiva. El ciclo sigue. */
    private const val IDLE_RESET_MS = 10 * 60_000L
    private const val MIN_WINDOW_MS = 60_000L
    private const val MAX_WINDOW_MS = 3 * 60 * 60_000L
    private const val EPISODE_DEDUP_MS = 60_000L

    private data class Click(val atMs: Long, val burst: Boolean)

    private val clicks = ArrayDeque<Click>()
    private var lastBannerAtMs = 0L
    private var windowStartMs = 0L
    private var warnedThisWindow = false
    private var pausedThisWindow = false

    @Volatile private var cachedEpisodes: List<Episode>? = null
    private var cachedLimit: Int? = null
    private var cachedWindowMs: Long? = null

    @Synchronized
    fun onRefreshClick(context: Context, settings: AppSettings, burst: Boolean = false): Decision {
        val now = System.currentTimeMillis()
        ensureLearned(context)
        val last = clicks.lastOrNull()?.atMs
        if (last != null && now - last > IDLE_RESET_MS) {
            windowStartMs = now
            warnedThisWindow = false
            pausedThisWindow = false
        }
        if (clicks.isEmpty()) {
            windowStartMs = now
        }
        clicks.addLast(Click(now, burst))
        while (clicks.size > MAX_TIMESTAMPS) clicks.removeFirst()

        val limit = cachedLimit ?: return Decision.NONE
        val windowMs = cachedWindowMs ?: return Decision.NONE
        val from = maxOf(windowStartMs, now - windowMs)
        val current = countSince(from)

        if (settings.refreshGuardPauseEnabled && !pausedThisWindow &&
            current >= threshold(limit, settings.refreshGuardPausePercent)
        ) {
            pausedThisWindow = true
            return Decision.PAUSE
        }
        if (settings.refreshGuardWarnEnabled && !warnedThisWindow &&
            current >= threshold(limit, settings.refreshGuardWarnPercent)
        ) {
            warnedThisWindow = true
            return Decision.WARN
        }
        return Decision.NONE
    }

    /** Cierra el ciclo al detectar el banner y empieza el siguiente. */
    @Synchronized
    fun onThrottleDetected(context: Context, burst: Boolean): Episode? {
        val now = System.currentTimeMillis()
        if (clicks.isEmpty()) return null
        ensureLearned(context)
        val prev = cachedEpisodes.orEmpty().lastOrNull()
        if (prev != null && now - prev.atMs < EPISODE_DEDUP_MS) return null
        val episode = buildEpisode(
            endedAtMs = now,
            live = false,
            fallbackBurst = burst,
        ) ?: return null
        saveEpisodes(context, (cachedEpisodes.orEmpty() + episode).takeLast(MAX_EPISODES))
        persistLastBanner(context, now)
        lastBannerAtMs = now
        resetCycleLocked()
        return episode
    }

    /** Solo flags preventivos; no borra el ciclo en curso (banner → banner). */
    @Synchronized
    fun resetSession() {
        warnedThisWindow = false
        pausedThisWindow = false
        windowStartMs = System.currentTimeMillis()
    }

    @Synchronized
    fun clearMemory(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_EPISODES).remove(KEY_LAST_BANNER).apply()
        cachedEpisodes = emptyList()
        cachedLimit = null
        cachedWindowMs = null
        lastBannerAtMs = 0L
        resetCycleLocked()
    }

    @Synchronized
    fun status(context: Context): Status {
        ensureLearned(context)
        val now = System.currentTimeMillis()
        val windowMs = cachedWindowMs
        val windowClicks = if (windowMs != null && clicks.isNotEmpty()) {
            countSince(maxOf(windowStartMs, now - windowMs))
        } else {
            clicks.size
        }
        return Status(
            sessionClicks = clicks.size,
            windowClicks = windowClicks,
            learnedLimit = cachedLimit,
            windowMs = windowMs,
            episodes = cachedEpisodes.orEmpty(),
            live = buildEpisode(endedAtMs = 0L, live = true, fallbackBurst = false),
            lastBannerAtMs = lastBannerAtMs,
        )
    }

    fun describeEpisode(e: Episode): String =
        "${e.clicks} clics en ${formatDuration(e.elapsedMs)} " +
            "(≈${"%.1f".format(Locale.US, e.clicksPerMin)}/min, ${e.clicksLast60s} en el último min" +
            (if (e.burst) ", ráfaga" else "") + ")"

    fun summaryText(context: Context): String {
        val s = status(context)
        if (s.episodes.isEmpty() && s.live == null) {
            return "Sin ciclos aún. Al salir el banner «demasiados toques» se cierra un ciclo " +
                "con todos los clics desde el aviso anterior. Clics ahora: ${s.sessionClicks}."
        }
        return buildString {
            append("Límite aprendido: ~").append(s.learnedLimit ?: "?").append(" clics")
            s.windowMs?.let { append(" en ").append(formatDuration(it)) }
            append(" · ").append(s.episodes.size).append(" ciclos cerrados")
            s.live?.let { live ->
                append('\n').append("Ciclo en curso: ").append(live.clicks).append(" clics")
                if (live.firstClickAtMs > 0L) {
                    append(" · ").append(formatDuration(System.currentTimeMillis() - live.firstClickAtMs))
                }
            }
        }
    }

    fun exportJson(context: Context): String {
        ensureLearned(context)
        return JSONObject()
            .put("episodes", episodesToArray(cachedEpisodes.orEmpty()))
            .put("lastBannerAt", lastBannerAtMs)
            .toString()
    }

    @Synchronized
    fun importJson(context: Context, json: String) {
        val trimmed = json.trim()
        val (episodes, bannerRaw) = parseBackup(trimmed)
        saveEpisodes(context, episodes.takeLast(MAX_EPISODES))
        lastBannerAtMs = if (bannerRaw > 0L) bannerRaw else episodes.lastOrNull()?.atMs ?: 0L
        persistLastBanner(context, lastBannerAtMs)
    }

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

    fun formatClock(ms: Long): String {
        if (ms <= 0L) return "—"
        return SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    private fun threshold(limit: Int, percent: Int): Int =
        (limit * percent / 100.0).toInt().coerceAtLeast(1)

    private fun resetCycleLocked() {
        clicks.clear()
        windowStartMs = 0L
        warnedThisWindow = false
        pausedThisWindow = false
    }

    private fun countSince(fromMs: Long): Int {
        var n = 0
        for (c in clicks.asReversed()) {
            if (c.atMs < fromMs) break
            n++
        }
        return n
    }

    private fun buildEpisode(endedAtMs: Long, live: Boolean, fallbackBurst: Boolean): Episode? {
        if (clicks.isEmpty()) return null
        val first = clicks.first()
        val last = clicks.last()
        val times = clicks.map { it.atMs }
        val gaps = times.zipWithNext { a, b -> b - a }
        val burstClicks = clicks.count { it.burst }
        val endRef = if (live) last.atMs else endedAtMs.coerceAtLeast(last.atMs)
        val prev = lastBannerAtMs
        val wait = if (prev > 0L && first.atMs >= prev) first.atMs - prev else 0L
        val cycleStart = if (prev > 0L) prev else first.atMs
        val elapsed = (endRef - first.atMs).coerceAtLeast(0L)
        val last60From = (if (live) System.currentTimeMillis() else endRef) - 60_000L
        val last5From = (if (live) System.currentTimeMillis() else endRef) - 5 * 60_000L
        return Episode(
            atMs = if (live) 0L else endedAtMs,
            clicks = clicks.size,
            elapsedMs = elapsed,
            clicksLast60s = times.count { it >= last60From },
            burst = burstClicks > 0 || fallbackBurst,
            previousBannerAtMs = prev,
            firstClickAtMs = first.atMs,
            lastClickAtMs = last.atMs,
            burstClicks = burstClicks,
            clicksLast5min = times.count { it >= last5From },
            minGapMs = gaps.minOrNull() ?: 0L,
            maxGapMs = gaps.maxOrNull() ?: 0L,
            avgGapMs = if (gaps.isEmpty()) 0L else gaps.sum() / gaps.size,
            idleGaps = gaps.count { it >= IDLE_RESET_MS },
            warned = warnedThisWindow,
            pausedPreventively = pausedThisWindow,
            waitAfterPreviousMs = wait,
            cycleMs = (endRef - cycleStart).coerceAtLeast(0L),
        )
    }

    private fun ensureLearned(context: Context) {
        if (cachedEpisodes != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_EPISODES, "[]") ?: "[]"
        val parsed = parseEpisodes(raw)
        lastBannerAtMs = prefs.getLong(KEY_LAST_BANNER, parsed.lastOrNull()?.atMs ?: 0L)
        recompute(parsed)
    }

    private fun recompute(episodes: List<Episode>) {
        cachedEpisodes = episodes
        val recent = episodes.takeLast(LEARN_FROM_LAST)
        if (recent.isEmpty()) {
            cachedLimit = null
            cachedWindowMs = null
            return
        }
        cachedLimit = median(recent.map { it.clicks.toLong() }).toInt().coerceAtLeast(1)
        cachedWindowMs = (median(recent.map { it.elapsedMs.coerceAtLeast(1L) }) * 5 / 4)
            .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun persistLastBanner(context: Context, atMs: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_BANNER, atMs).apply()
    }

    private fun saveEpisodes(context: Context, episodes: List<Episode>) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EPISODES, episodesToArray(episodes).toString()).apply()
        recompute(episodes)
    }

    private fun episodesToArray(episodes: List<Episode>): JSONArray {
        val arr = JSONArray()
        episodes.forEach { e ->
            arr.put(
                JSONObject()
                    .put("at", e.atMs)
                    .put("clicks", e.clicks)
                    .put("elapsedMs", e.elapsedMs)
                    .put("last60s", e.clicksLast60s)
                    .put("burst", e.burst)
                    .put("prevBanner", e.previousBannerAtMs)
                    .put("firstClick", e.firstClickAtMs)
                    .put("lastClick", e.lastClickAtMs)
                    .put("burstClicks", e.burstClicks)
                    .put("last5min", e.clicksLast5min)
                    .put("minGap", e.minGapMs)
                    .put("maxGap", e.maxGapMs)
                    .put("avgGap", e.avgGapMs)
                    .put("idleGaps", e.idleGaps)
                    .put("warned", e.warned)
                    .put("paused", e.pausedPreventively)
                    .put("waitPrev", e.waitAfterPreviousMs)
                    .put("cycleMs", e.cycleMs),
            )
        }
        return arr
    }

    private fun parseBackup(json: String): Pair<List<Episode>, Long> = try {
        val trimmed = json.trim().removePrefix("\uFEFF")
        when {
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                val arr = root.optJSONArray("episodes") ?: JSONArray()
                parseEpisodeArray(arr) to root.optLong("lastBannerAt", 0L)
            }
            trimmed.startsWith("[") -> parseEpisodeArray(JSONArray(trimmed)) to 0L
            else -> emptyList<Episode>() to 0L
        }
    } catch (_: Exception) {
        emptyList<Episode>() to 0L
    }

    private fun parseEpisodes(json: String): List<Episode> = parseBackup(json).first

    private fun parseEpisodeArray(arr: JSONArray): List<Episode> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val clickCount = o.optInt("clicks", 0)
            if (clickCount <= 0) return@mapNotNull null
            val at = o.optLong("at", 0L)
            val elapsed = o.optLong("elapsedMs", 0L)
            val first = o.optLong("firstClick", 0L)
            val last = o.optLong("lastClick", 0L)
            Episode(
                atMs = at,
                clicks = clickCount,
                elapsedMs = elapsed,
                clicksLast60s = o.optInt("last60s", 0),
                burst = o.optBoolean("burst", false),
                previousBannerAtMs = o.optLong("prevBanner", 0L),
                firstClickAtMs = first,
                lastClickAtMs = last,
                burstClicks = o.optInt("burstClicks", if (o.optBoolean("burst", false)) clickCount else 0),
                clicksLast5min = o.optInt("last5min", 0),
                minGapMs = o.optLong("minGap", 0L),
                maxGapMs = o.optLong("maxGap", 0L),
                avgGapMs = o.optLong("avgGap", 0L),
                idleGaps = o.optInt("idleGaps", 0),
                warned = o.optBoolean("warned", false),
                pausedPreventively = o.optBoolean("paused", false),
                waitAfterPreviousMs = o.optLong("waitPrev", 0L),
                cycleMs = o.optLong(
                    "cycleMs",
                    if (o.optLong("prevBanner", 0L) > 0L && at > 0L) {
                        at - o.optLong("prevBanner", 0L)
                    } else {
                        elapsed
                    },
                ),
            )
        }
}
