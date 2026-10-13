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
 * y cuánto tiempo hicieron falta. Avisa / pausa antes de volver a llegar a ese límite.
 */
object RefreshClickGuard {

    enum class Decision { NONE, WARN, PAUSE }

    data class Episode(
        val atMs: Long,
        val clicks: Int,
        val elapsedMs: Long,
        val clicksLast60s: Int,
        val burst: Boolean,
    ) {
        val clicksPerMin: Double
            get() = if (elapsedMs > 0) clicks * 60_000.0 / elapsedMs else 0.0
    }

    data class Status(
        val sessionClicks: Int,
        val windowClicks: Int,
        val learnedLimit: Int?,
        val windowMs: Long?,
        val episodes: List<Episode>,
    )

    private const val PREFS = "pichix_refresh_guard"
    private const val KEY_EPISODES = "episodes"
    private const val MAX_EPISODES = 20
    private const val LEARN_FROM_LAST = 5
    private const val MAX_TIMESTAMPS = 6000
    /** Sin clics durante este tiempo = Flex ya «olvidó» la racha; empieza sesión nueva. */
    private const val IDLE_RESET_MS = 10 * 60_000L
    private const val MIN_WINDOW_MS = 60_000L
    private const val MAX_WINDOW_MS = 3 * 60 * 60_000L
    private const val EPISODE_DEDUP_MS = 60_000L

    private val clickTimes = ArrayDeque<Long>()
    private var sessionStartMs = 0L
    private var sessionClicks = 0
    private var warnedThisSession = false
    private var pausedThisSession = false

    @Volatile private var cachedEpisodes: List<Episode>? = null
    private var cachedLimit: Int? = null
    private var cachedWindowMs: Long? = null

    @Synchronized
    fun onRefreshClick(context: Context, settings: AppSettings): Decision {
        val now = System.currentTimeMillis()
        val last = clickTimes.lastOrNull()
        if (last != null && now - last > IDLE_RESET_MS) resetSessionLocked()
        if (sessionClicks == 0) sessionStartMs = now
        sessionClicks++
        clickTimes.addLast(now)
        while (clickTimes.size > MAX_TIMESTAMPS) clickTimes.removeFirst()

        ensureLearned(context)
        val limit = cachedLimit ?: return Decision.NONE
        val windowMs = cachedWindowMs ?: return Decision.NONE
        val current = countSince(maxOf(now - windowMs, sessionStartMs))

        if (settings.refreshGuardPauseEnabled && !pausedThisSession &&
            current >= threshold(limit, settings.refreshGuardPausePercent)
        ) {
            pausedThisSession = true
            return Decision.PAUSE
        }
        if (settings.refreshGuardWarnEnabled && !warnedThisSession &&
            current >= threshold(limit, settings.refreshGuardWarnPercent)
        ) {
            warnedThisSession = true
            return Decision.WARN
        }
        return Decision.NONE
    }

    /** Primera detección del banner: guarda el episodio y reinicia la sesión. */
    @Synchronized
    fun onThrottleDetected(context: Context, burst: Boolean): Episode? {
        val now = System.currentTimeMillis()
        if (sessionClicks <= 0) return null
        val episodes = loadEpisodes(context)
        val prev = episodes.lastOrNull()
        if (prev != null && now - prev.atMs < EPISODE_DEDUP_MS) return null
        val episode = Episode(
            atMs = now,
            clicks = sessionClicks,
            elapsedMs = (now - sessionStartMs).coerceAtLeast(0L),
            clicksLast60s = countSince(now - 60_000L),
            burst = burst,
        )
        saveEpisodes(context, (episodes + episode).takeLast(MAX_EPISODES))
        resetSessionLocked()
        return episode
    }

    @Synchronized
    fun resetSession() = resetSessionLocked()

    @Synchronized
    fun clearMemory(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_EPISODES).apply()
        cachedEpisodes = emptyList()
        cachedLimit = null
        cachedWindowMs = null
        resetSessionLocked()
    }

    @Synchronized
    fun status(context: Context): Status {
        ensureLearned(context)
        val now = System.currentTimeMillis()
        val windowMs = cachedWindowMs
        val windowClicks = if (windowMs != null && sessionClicks > 0) {
            countSince(maxOf(now - windowMs, sessionStartMs))
        } else {
            sessionClicks
        }
        return Status(
            sessionClicks = sessionClicks,
            windowClicks = windowClicks,
            learnedLimit = cachedLimit,
            windowMs = windowMs,
            episodes = cachedEpisodes.orEmpty(),
        )
    }

    fun describeEpisode(e: Episode): String =
        "${e.clicks} clics en ${formatDuration(e.elapsedMs)} " +
            "(≈${"%.1f".format(Locale.US, e.clicksPerMin)}/min, ${e.clicksLast60s} en el último min" +
            (if (e.burst) ", ráfaga" else "") + ")"

    fun summaryText(context: Context): String {
        val s = status(context)
        if (s.episodes.isEmpty()) {
            return "Sin datos aún. Al primer banner «demasiados toques» se guardará a cuántos clics " +
                "y en cuánto tiempo salió. Clics en esta racha: ${s.sessionClicks}."
        }
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        return buildString {
            append("Límite aprendido: ~").append(s.learnedLimit ?: "?").append(" clics")
            s.windowMs?.let { append(" en ").append(formatDuration(it)) }
            append(" (").append(s.episodes.size).append(" detenciones)\n")
            append("Racha actual: ").append(s.windowClicks).append(" clics en ventana · ")
                .append(s.sessionClicks).append(" desde el inicio\n")
            s.episodes.takeLast(3).reversed().forEach { e ->
                append("• ").append(fmt.format(Date(e.atMs))).append(": ").append(describeEpisode(e)).append('\n')
            }
        }.trimEnd()
    }

    fun exportJson(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EPISODES, "[]") ?: "[]"

    @Synchronized
    fun importJson(context: Context, json: String) {
        val parsed = parseEpisodes(json)
        saveEpisodes(context, parsed.takeLast(MAX_EPISODES))
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

    private fun threshold(limit: Int, percent: Int): Int =
        (limit * percent / 100.0).toInt().coerceAtLeast(1)

    private fun resetSessionLocked() {
        clickTimes.clear()
        sessionStartMs = 0L
        sessionClicks = 0
        warnedThisSession = false
        pausedThisSession = false
    }

    private fun countSince(fromMs: Long): Int {
        var n = 0
        for (t in clickTimes.asReversed()) {
            if (t < fromMs) break
            n++
        }
        return n
    }

    private fun ensureLearned(context: Context) {
        if (cachedEpisodes != null) return
        recompute(loadEpisodesFromPrefs(context))
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
        cachedWindowMs = (median(recent.map { it.elapsedMs }) * 5 / 4)
            .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun loadEpisodes(context: Context): List<Episode> {
        ensureLearned(context)
        return cachedEpisodes.orEmpty()
    }

    private fun loadEpisodesFromPrefs(context: Context): List<Episode> =
        parseEpisodes(exportJson(context))

    private fun saveEpisodes(context: Context, episodes: List<Episode>) {
        val arr = JSONArray()
        episodes.forEach { e ->
            arr.put(
                JSONObject()
                    .put("at", e.atMs)
                    .put("clicks", e.clicks)
                    .put("elapsedMs", e.elapsedMs)
                    .put("last60s", e.clicksLast60s)
                    .put("burst", e.burst),
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EPISODES, arr.toString()).apply()
        recompute(episodes)
    }

    private fun parseEpisodes(json: String): List<Episode> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val clicks = o.optInt("clicks", 0)
            if (clicks <= 0) return@mapNotNull null
            Episode(
                atMs = o.optLong("at", 0L),
                clicks = clicks,
                elapsedMs = o.optLong("elapsedMs", 0L),
                clicksLast60s = o.optInt("last60s", 0),
                burst = o.optBoolean("burst", false),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
