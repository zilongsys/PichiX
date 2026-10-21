package com.oceanlab.pichix.service

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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

    const val ACTION_CHANGED = "com.oceanlab.pichix.REFRESH_GUARD_CHANGED"

    enum class Decision { NONE, WARN, PAUSE }

    /**
     * Ciclo: del banner anterior (o del primer clic) hasta el banner que lo cierra.
     * [atMs] = 0 → ciclo en curso.
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
        val activeClickingMs: Long = 0L,
    ) {
        val clickingMs: Long
            get() = if (firstClickAtMs > 0L && lastClickAtMs >= firstClickAtMs) {
                lastClickAtMs - firstClickAtMs
            } else {
                elapsedMs
            }
        val clicksPerMin: Double
            get() {
                val denom = when {
                    activeClickingMs > 0L -> activeClickingMs
                    idleGaps == 0 -> clickingMs
                    else -> 0L
                }
                return RefreshClickMath.pacePerMin(clicks, denom)
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
        val warnAt: Int? = null,
        val pauseAt: Int? = null,
    ) {
        val progressPercent: Int
            get() {
                val limit = learnedLimit ?: return 0
                if (limit <= 0) return 0
                return ((windowClicks * 100.0) / limit).toInt().coerceIn(0, 100)
            }
    }

    private const val PREFS = "pichix_refresh_guard"
    private const val KEY_EPISODES = "episodes"
    private const val KEY_LAST_BANNER = "last_banner_at"
    private const val KEY_LIVE = "live_clicks"
    private const val KEY_WINDOW_START = "live_window_start"
    private const val KEY_WARNED = "live_warned"
    private const val KEY_PAUSED = "live_paused"

    private val clicks = ArrayDeque<RefreshClickMath.Click>()
    private var lastBannerAtMs = 0L
    private var windowStartMs = 0L
    private var warnedThisWindow = false
    private var pausedThisWindow = false
    private var persistDirty = 0
    private var lastPersistMs = 0L

    @Volatile private var cachedEpisodes: List<Episode>? = null
    private var cachedLimit: Int? = null
    private var cachedWindowMs: Long? = null

    @Synchronized
    fun onRefreshClick(context: Context, settings: AppSettings, burst: Boolean = false): Decision {
        val now = System.currentTimeMillis()
        ensureLearned(context)
        val last = clicks.lastOrNull()?.atMs
        if (last != null && now - last > RefreshClickMath.IDLE_RESET_MS) {
            windowStartMs = now
            warnedThisWindow = false
            pausedThisWindow = false
        }
        if (clicks.isEmpty()) windowStartMs = now
        clicks.addLast(RefreshClickMath.Click(now, burst))
        while (clicks.size > RefreshClickMath.MAX_TIMESTAMPS) clicks.removeFirst()
        persistDirty++
        maybePersistLive(context, now)

        val limit = cachedLimit ?: return Decision.NONE
        val windowMs = cachedWindowMs ?: return Decision.NONE
        val from = maxOf(windowStartMs, now - windowMs)
        val current = RefreshClickMath.countSince(clicks.toList(), from)
        val math = RefreshClickMath.decide(
            current = current,
            limit = limit,
            warnPct = settings.refreshGuardWarnPercent,
            pausePct = settings.refreshGuardPausePercent,
            warnEnabled = settings.refreshGuardWarnEnabled,
            pauseEnabled = settings.refreshGuardPauseEnabled,
            alreadyWarned = warnedThisWindow,
            alreadyPaused = pausedThisWindow,
        )
        val decision = when (math) {
            RefreshClickMath.Decision.PAUSE -> {
                pausedThisWindow = true
                persistLiveNow(context)
                Decision.PAUSE
            }
            RefreshClickMath.Decision.WARN -> {
                warnedThisWindow = true
                persistLiveNow(context)
                Decision.WARN
            }
            RefreshClickMath.Decision.NONE -> Decision.NONE
        }
        notifyChanged(context)
        return decision
    }

    @Synchronized
    fun onThrottleDetected(context: Context, burst: Boolean): Episode? {
        val now = System.currentTimeMillis()
        ensureLearned(context)
        if (clicks.isEmpty()) return null
        val prev = cachedEpisodes.orEmpty().lastOrNull()
        if (RefreshClickMath.shouldDedupBanner(prev?.atMs ?: 0L, now)) return null
        val episode = buildEpisode(endedAtMs = now, live = false, fallbackBurst = burst) ?: return null
        saveEpisodes(context, (cachedEpisodes.orEmpty() + episode).takeLast(RefreshClickMath.MAX_EPISODES))
        lastBannerAtMs = now
        persistLastBanner(context, now)
        resetCycleLocked()
        persistLiveNow(context)
        notifyChanged(context)
        return episode
    }

    @Synchronized
    fun resetSession() {
        warnedThisWindow = false
        pausedThisWindow = false
        windowStartMs = System.currentTimeMillis()
    }

    @Synchronized
    fun clearMemory(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_EPISODES)
            .remove(KEY_LAST_BANNER)
            .remove(KEY_LIVE)
            .remove(KEY_WINDOW_START)
            .remove(KEY_WARNED)
            .remove(KEY_PAUSED)
            .apply()
        cachedEpisodes = emptyList()
        cachedLimit = null
        cachedWindowMs = null
        lastBannerAtMs = 0L
        resetCycleLocked()
        persistDirty = 0
        notifyChanged(context)
    }

    @Synchronized
    fun status(context: Context): Status {
        ensureLearned(context)
        val now = System.currentTimeMillis()
        val windowMs = cachedWindowMs
        val windowClicks = if (windowMs != null && clicks.isNotEmpty()) {
            RefreshClickMath.countSince(clicks.toList(), maxOf(windowStartMs, now - windowMs))
        } else {
            clicks.size
        }
        val limit = cachedLimit
        val (warnPct, pausePct) = RefreshClickMath.orderedPercents(
            AppSettings(context).refreshGuardWarnPercent,
            AppSettings(context).refreshGuardPausePercent,
        )
        return Status(
            sessionClicks = clicks.size,
            windowClicks = windowClicks,
            learnedLimit = limit,
            windowMs = windowMs,
            episodes = cachedEpisodes.orEmpty(),
            live = buildEpisode(endedAtMs = 0L, live = true, fallbackBurst = false),
            lastBannerAtMs = lastBannerAtMs,
            warnAt = limit?.let { RefreshClickMath.threshold(it, warnPct) },
            pauseAt = limit?.let { RefreshClickMath.threshold(it, pausePct) },
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
            if (s.warnAt != null && s.pauseAt != null) {
                append('\n').append("Aviso ~").append(s.warnAt)
                    .append(" · pausa ~").append(s.pauseAt)
                    .append(" · ventana ").append(s.windowClicks)
                    .append(" (").append(s.progressPercent).append("%)")
            }
            s.live?.let { live ->
                append('\n').append("Ciclo en curso: ").append(live.clicks).append(" clics")
                val active = if (live.activeClickingMs > 0L) live.activeClickingMs else live.clickingMs
                if (active > 0L) {
                    append(" · ").append(formatDuration(active))
                    append(" dando clic · ≈")
                    append("%.1f".format(Locale.US, live.clicksPerMin)).append("/min")
                }
            }
        }
    }

    @Synchronized
    fun exportJson(context: Context): String {
        ensureLearned(context)
        persistLiveNow(context)
        return JSONObject()
            .put("episodes", episodesToArray(cachedEpisodes.orEmpty()))
            .put("lastBannerAt", lastBannerAtMs)
            .put("live_clicks", liveClicksToArray())
            .put("liveWindowStart", windowStartMs)
            .put("liveWarned", warnedThisWindow)
            .put("livePaused", pausedThisWindow)
            .toString()
    }

    @Synchronized
    fun importJson(context: Context, json: String) {
        val trimmed = json.trim()
        val parsed = parseBackup(trimmed)
        saveEpisodes(context, parsed.episodes.takeLast(RefreshClickMath.MAX_EPISODES))
        lastBannerAtMs = if (parsed.lastBannerAt > 0L) {
            parsed.lastBannerAt
        } else {
            parsed.episodes.lastOrNull()?.atMs ?: 0L
        }
        persistLastBanner(context, lastBannerAtMs)
        clicks.clear()
        parsed.live.forEach { clicks.addLast(it) }
        while (clicks.size > RefreshClickMath.MAX_TIMESTAMPS) clicks.removeFirst()
        windowStartMs = parsed.windowStart
        warnedThisWindow = parsed.warned
        pausedThisWindow = parsed.paused
        persistLiveNow(context)
        notifyChanged(context)
    }

    fun formatDuration(ms: Long): String = RefreshClickMath.formatDuration(ms)

    fun formatClock(ms: Long): String {
        if (ms <= 0L) return "—"
        return SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    private fun buildEpisode(endedAtMs: Long, live: Boolean, fallbackBurst: Boolean): Episode? {
        val snap = RefreshClickMath.snapshot(
            clicks = clicks.toList(),
            nowMs = System.currentTimeMillis(),
            lastBannerAtMs = lastBannerAtMs,
            live = live,
            endedAtMs = endedAtMs,
            fallbackBurst = fallbackBurst,
        ) ?: return null
        return Episode(
            atMs = if (live) 0L else endedAtMs,
            clicks = snap.clicks,
            elapsedMs = snap.elapsedMs,
            clicksLast60s = snap.clicksLast60s,
            burst = snap.burst,
            previousBannerAtMs = lastBannerAtMs,
            firstClickAtMs = snap.firstClickAtMs,
            lastClickAtMs = snap.lastClickAtMs,
            burstClicks = snap.burstClicks,
            clicksLast5min = snap.clicksLast5min,
            minGapMs = snap.minGapMs,
            maxGapMs = snap.maxGapMs,
            avgGapMs = snap.avgGapMs,
            idleGaps = snap.idleGaps,
            warned = warnedThisWindow,
            pausedPreventively = pausedThisWindow,
            waitAfterPreviousMs = snap.waitAfterPreviousMs,
            cycleMs = snap.cycleMs,
            activeClickingMs = snap.activeClickingMs,
        )
    }

    private fun resetCycleLocked() {
        clicks.clear()
        windowStartMs = 0L
        warnedThisWindow = false
        pausedThisWindow = false
        persistDirty = 0
    }

    private fun ensureLearned(context: Context) {
        if (cachedEpisodes != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_EPISODES, "[]") ?: "[]"
        val parsed = parseEpisodes(raw)
        lastBannerAtMs = prefs.getLong(KEY_LAST_BANNER, parsed.lastOrNull()?.atMs ?: 0L)
        recompute(parsed)
        restoreLive(prefs)
    }

    private fun restoreLive(prefs: android.content.SharedPreferences) {
        clicks.clear()
        parseClickArray(prefs.getString(KEY_LIVE, "[]") ?: "[]").forEach { clicks.addLast(it) }
        while (clicks.size > RefreshClickMath.MAX_TIMESTAMPS) clicks.removeFirst()
        windowStartMs = prefs.getLong(KEY_WINDOW_START, clicks.firstOrNull()?.atMs ?: 0L)
        warnedThisWindow = prefs.getBoolean(KEY_WARNED, false)
        pausedThisWindow = prefs.getBoolean(KEY_PAUSED, false)
    }

    private fun recompute(episodes: List<Episode>) {
        cachedEpisodes = episodes
        cachedLimit = RefreshClickMath.learnedLimit(episodes.map { it.clicks })
        cachedWindowMs = RefreshClickMath.learnedWindowMs(episodes.map { it.elapsedMs })
    }

    private fun maybePersistLive(context: Context, now: Long) {
        if (persistDirty < RefreshClickMath.PERSIST_EVERY_CLICKS &&
            now - lastPersistMs < RefreshClickMath.PERSIST_EVERY_MS
        ) {
            return
        }
        persistLiveNow(context)
    }

    private fun persistLiveNow(context: Context) {
        lastPersistMs = System.currentTimeMillis()
        persistDirty = 0
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIVE, liveClicksToArray().toString())
            .putLong(KEY_WINDOW_START, windowStartMs)
            .putBoolean(KEY_WARNED, warnedThisWindow)
            .putBoolean(KEY_PAUSED, pausedThisWindow)
            .apply()
        notifyChanged(context)
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

    private fun liveClicksToArray(): JSONArray {
        val arr = JSONArray()
        clicks.forEach { c ->
            arr.put(JSONObject().put("t", c.atMs).put("b", c.burst))
        }
        return arr
    }

    private fun notifyChanged(context: Context) {
        try {
            LocalBroadcastManager.getInstance(context.applicationContext)
                .sendBroadcast(Intent(ACTION_CHANGED))
        } catch (_: Exception) {
        }
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
                    .put("cycleMs", e.cycleMs)
                    .put("activeMs", e.activeClickingMs),
            )
        }
        return arr
    }

    private data class BackupParse(
        val episodes: List<Episode>,
        val lastBannerAt: Long,
        val live: List<RefreshClickMath.Click>,
        val windowStart: Long,
        val warned: Boolean,
        val paused: Boolean,
    )

    private fun parseBackup(json: String): BackupParse = try {
        val trimmed = json.trim().removePrefix("\uFEFF")
        when {
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                val arr = root.optJSONArray("episodes") ?: JSONArray()
                BackupParse(
                    episodes = parseEpisodeArray(arr),
                    lastBannerAt = root.optLong("lastBannerAt", 0L),
                    live = parseClickArrayObj(root.optJSONArray("live_clicks")),
                    windowStart = root.optLong("liveWindowStart", 0L),
                    warned = root.optBoolean("liveWarned", false),
                    paused = root.optBoolean("livePaused", false),
                )
            }
            trimmed.startsWith("[") -> BackupParse(
                episodes = parseEpisodeArray(JSONArray(trimmed)),
                lastBannerAt = 0L,
                live = emptyList(),
                windowStart = 0L,
                warned = false,
                paused = false,
            )
            else -> BackupParse(emptyList(), 0L, emptyList(), 0L, false, false)
        }
    } catch (_: Exception) {
        BackupParse(emptyList(), 0L, emptyList(), 0L, false, false)
    }

    private fun parseEpisodes(json: String): List<Episode> = parseBackup(json).episodes

    private fun parseClickArray(json: String): List<RefreshClickMath.Click> = try {
        parseClickArrayObj(JSONArray(json))
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseClickArrayObj(arr: JSONArray?): List<RefreshClickMath.Click> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optLong("t", 0L)
            if (t <= 0L) return@mapNotNull null
            RefreshClickMath.Click(t, o.optBoolean("b", false))
        }
    }

    private fun parseEpisodeArray(arr: JSONArray): List<Episode> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val clickCount = o.optInt("clicks", 0)
            if (clickCount <= 0) return@mapNotNull null
            val at = o.optLong("at", 0L)
            val elapsed = o.optLong("elapsedMs", 0L)
            Episode(
                atMs = at,
                clicks = clickCount,
                elapsedMs = elapsed,
                clicksLast60s = o.optInt("last60s", 0),
                burst = o.optBoolean("burst", false),
                previousBannerAtMs = o.optLong("prevBanner", 0L),
                firstClickAtMs = o.optLong("firstClick", 0L),
                lastClickAtMs = o.optLong("lastClick", 0L),
                burstClicks = o.optInt("burstClicks", if (o.optBoolean("burst", false)) clickCount else 0),
                clicksLast5min = o.optInt("last5min", 0),
                minGapMs = o.optLong("minGap", 0L),
                maxGapMs = o.optLong("maxGap", 0L),
                avgGapMs = o.optLong("avgGap", 0L),
                idleGaps = o.optInt("idleGaps", 0),
                warned = o.optBoolean("warned", false),
                pausedPreventively = o.optBoolean("paused", false),
                waitAfterPreviousMs = o.optLong("waitPrev", 0L),
                activeClickingMs = o.optLong("activeMs", 0L),
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
