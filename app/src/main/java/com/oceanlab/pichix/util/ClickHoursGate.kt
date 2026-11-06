package com.oceanlab.pichix.util

import android.content.Context
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus

/**
 * Caché del horario automático. El grabber solo lee [shouldWait] / [idleDelayMs];
 * el CSV se lee al recalcular (arranque, Config, tomas nuevas).
 */
object ClickHoursGate {

    @Volatile private var plan: ClickHoursPlanner.Plan? = null
    @Volatile private var settingsKey: String = ""
    @Volatile private var computedAt: Long = 0L
    @Volatile private var recomputePosted: Boolean = false
    @Volatile var waiting: Boolean = false
        private set

    fun invalidate() {
        plan = null
        computedAt = 0L
        settingsKey = ""
    }

    fun recompute(context: Context): ClickHoursPlanner.Plan {
        val app = context.applicationContext
        val settings = AppSettings(app)
        val key = settingsKeyOf(settings)
        val now = System.currentTimeMillis()
        if (!settings.clickGoodHoursEnabled) {
            val disabled = ClickHoursPlanner.Plan(
                alwaysClick = true,
                windowsByWeekday = List(7) { emptyList() },
                acceptCount = 0,
                reason = "off",
            )
            plan = disabled
            settingsKey = key
            computedAt = now
            waiting = false
            return disabled
        }
        val since = now - ClickHoursPlanner.LOOKBACK_MS
        val accepts = try {
            OfferLogger(app).getEntriesSince(since)
                .filter { it.status == OfferStatus.ACCEPTED }
                .map { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
        val computed = ClickHoursPlanner.compute(
            acceptAtMs = accepts,
            nowMs = now,
            params = ClickHoursPlanner.Params(
                marginMin = settings.clickGoodHoursMarginMin,
                ofBestPct = settings.clickGoodHoursPct,
                minAccepts = settings.clickGoodHoursMinAccepts,
            ),
        )
        plan = computed
        settingsKey = key
        computedAt = now
        return computed
    }

    fun currentPlan(): ClickHoursPlanner.Plan? = plan

    fun shouldWait(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val settings = AppSettings(context)
        if (!settings.clickGoodHoursEnabled) {
            waiting = false
            return false
        }
        val cached = plan
        if (cached == null || settingsKey != settingsKeyOf(settings) ||
            nowMs - computedAt > ClickHoursPlanner.STALE_MS
        ) {
            requestRecomputeAsync(context)
            waiting = false
            return false
        }
        if (cached.alwaysClick) {
            waiting = false
            return false
        }
        val wait = !cached.contains(nowMs)
        waiting = wait
        return wait
    }

    fun idleDelayMs(context: Context, nowMs: Long = System.currentTimeMillis()): Long {
        if (!shouldWait(context, nowMs)) return 0L
        val wait = plan?.millisUntilOpen(nowMs) ?: 0L
        return ClickHoursPlanner.idleDelayMs(wait)
    }

    fun statusText(nowMs: Long = System.currentTimeMillis()): String {
        val cached = plan ?: return ""
        return when (cached.reason) {
            "off" -> ""
            "few_data" ->
                "Aún no hay suficientes tomas ACEPTADA en 30 días (${cached.acceptCount}); se clica siempre."
            else -> {
                val today = cached.todayLabel(nowMs)
                val next = cached.nextStartLabel(nowMs)
                when {
                    cached.contains(nowMs) -> "$today · en franja"
                    next != null -> "$today · esperando $next"
                    else -> today
                }
            }
        }
    }

    fun waitLabel(nowMs: Long = System.currentTimeMillis()): String? {
        val cached = plan ?: return null
        if (cached.alwaysClick || cached.contains(nowMs)) return null
        return cached.nextStartLabel(nowMs)
    }

    fun needsRefresh(settings: AppSettings, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!settings.clickGoodHoursEnabled) return plan?.reason != "off"
        if (plan == null) return true
        if (settingsKey != settingsKeyOf(settings)) return true
        return nowMs - computedAt > ClickHoursPlanner.STALE_MS
    }

    private fun requestRecomputeAsync(context: Context) {
        if (recomputePosted) return
        recomputePosted = true
        val app = context.applicationContext
        Thread {
            try {
                recompute(app)
            } catch (_: Exception) {
            } finally {
                recomputePosted = false
            }
        }.start()
    }

    private fun settingsKeyOf(settings: AppSettings): String =
        "${settings.clickGoodHoursEnabled}|${settings.clickGoodHoursMarginMin}|${settings.clickGoodHoursPct}|${settings.clickGoodHoursMinAccepts}"
}
