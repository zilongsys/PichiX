package com.oceanlab.pichix.util

import java.util.Calendar
import java.util.Locale

/**
 * Calcula franjas de clic a partir de tomas ACEPTADA (día de la semana + hora local).
 * Sin Android: el gate cachea el resultado y el grabber solo consulta la caché.
 */
object ClickHoursPlanner {

    const val LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000
    const val STALE_MS = 12L * 60 * 60 * 1000
    const val MINUTES_IN_DAY = 24 * 60
    const val IDLE_POLL_MIN_MS = 5_000L
    const val IDLE_POLL_MAX_MS = 30_000L

    /** 0 = domingo (Calendar.DAY_OF_WEEK - 1). */
    val WEEKDAY_SHORT = arrayOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")
    /** Semana en orden habitual (lunes primero). */
    val WEEKDAY_DISPLAY_ORDER = intArrayOf(1, 2, 3, 4, 5, 6, 0)

    data class Params(
        val marginMin: Int = 30,
        val ofBestPct: Int = 50,
        val minAccepts: Int = 10,
    )

    data class Window(val startMin: Int, val endMin: Int) {
        fun contains(minute: Int): Boolean {
            val m = minute.coerceIn(0, MINUTES_IN_DAY - 1)
            return if (startMin <= endMin) m in startMin..endMin else m >= startMin || m <= endMin
        }

        fun label(): String = "${fmt(startMin)}–${fmt(endMin)}"
    }

    data class Plan(
        val alwaysClick: Boolean,
        val windowsByWeekday: List<List<Window>>,
        val acceptCount: Int,
        val reason: String,
    ) {
        fun contains(nowMs: Long): Boolean {
            if (alwaysClick) return true
            val (wd, minute) = weekdayAndMinute(nowMs)
            val day = windowsByWeekday.getOrNull(wd).orEmpty()
            return day.any { it.contains(minute) }
        }

        fun millisUntilOpen(nowMs: Long): Long {
            if (alwaysClick || contains(nowMs)) return 0L
            var best = Long.MAX_VALUE
            for (dayOffset in 0..7) {
                val cal = Calendar.getInstance()
                cal.timeInMillis = nowMs
                cal.add(Calendar.DAY_OF_YEAR, dayOffset)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val wd = cal.get(Calendar.DAY_OF_WEEK) - 1
                for (w in windowsByWeekday.getOrNull(wd).orEmpty()) {
                    cal.set(Calendar.HOUR_OF_DAY, w.startMin / 60)
                    cal.set(Calendar.MINUTE, w.startMin % 60)
                    val startAt = cal.timeInMillis
                    if (startAt > nowMs) best = minOf(best, startAt - nowMs)
                }
            }
            return if (best == Long.MAX_VALUE) 0L else best
        }

        fun todayLabel(nowMs: Long): String {
            if (alwaysClick) return ""
            val (wd, _) = weekdayAndMinute(nowMs)
            val day = windowsByWeekday.getOrNull(wd).orEmpty()
            if (day.isEmpty()) return "Hoy: sin franja"
            return "Hoy: " + day.joinToString(", ") { it.label() }
        }

        fun nextStartLabel(nowMs: Long): String? {
            if (alwaysClick || contains(nowMs)) return null
            val wait = millisUntilOpen(nowMs)
            if (wait <= 0L) return null
            val cal = Calendar.getInstance()
            cal.timeInMillis = nowMs + wait
            return "%02d:%02d".format(
                Locale.US,
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
            )
        }

        fun weekScheduleText(nowMs: Long): String {
            if (alwaysClick) return ""
            val (todayWd, _) = weekdayAndMinute(nowMs)
            return WEEKDAY_DISPLAY_ORDER.joinToString("\n") { wd ->
                val name = WEEKDAY_SHORT.getOrElse(wd) { "?" }
                val day = windowsByWeekday.getOrNull(wd).orEmpty()
                val hours = if (day.isEmpty()) "sin franja" else day.joinToString(", ") { it.label() }
                val mark = if (wd == todayWd) "  ← hoy" else ""
                "$name  $hours$mark"
            }
        }
    }

    fun compute(acceptAtMs: List<Long>, nowMs: Long, params: Params): Plan {
        val since = nowMs - LOOKBACK_MS
        val recent = acceptAtMs.filter { it in since..nowMs }
        val margin = params.marginMin.coerceIn(0, 90)
        val pct = params.ofBestPct.coerceIn(30, 90)
        val minAccepts = params.minAccepts.coerceIn(3, 50)
        if (recent.size < minAccepts) {
            return Plan(
                alwaysClick = true,
                windowsByWeekday = List(7) { emptyList() },
                acceptCount = recent.size,
                reason = "few_data",
            )
        }
        val scores = Array(7) { DoubleArray(24) }
        val counts = Array(7) { IntArray(24) }
        recent.forEach { ts ->
            val (wd, minute) = weekdayAndMinute(ts)
            val hour = (minute / 60).coerceIn(0, 23)
            counts[wd][hour]++
            scores[wd][hour] += 1.0
        }
        val globalGood = linkedSetOf<Int>()
        for (wd in 0..6) {
            val maxScore = scores[wd].maxOrNull() ?: 0.0
            if (maxScore <= 0.0) continue
            val floor = maxScore * pct / 100.0
            for (h in 0..23) {
                if (counts[wd][h] >= 1 && scores[wd][h] >= floor) globalGood.add(h)
            }
        }
        val byDay = (0..6).map { wd ->
            val maxScore = scores[wd].maxOrNull() ?: 0.0
            val hours = linkedSetOf<Int>()
            if (maxScore > 0.0) {
                val floor = maxScore * pct / 100.0
                for (h in 0..23) {
                    if (counts[wd][h] >= 1 && scores[wd][h] >= floor) hours.add(h)
                }
            } else {
                hours.addAll(globalGood)
            }
            mergeMarkedMinutes(expandHours(hours, margin))
        }
        return Plan(
            alwaysClick = false,
            windowsByWeekday = byDay,
            acceptCount = recent.size,
            reason = "ok",
        )
    }

    fun expandHours(hours: Set<Int>, marginMin: Int): BooleanArray {
        val mark = BooleanArray(MINUTES_IN_DAY)
        for (h in hours) {
            val hour = h.coerceIn(0, 23)
            val from = hour * 60 - marginMin
            val to = hour * 60 + 59 + marginMin
            var m = from
            while (m <= to) {
                mark[((m % MINUTES_IN_DAY) + MINUTES_IN_DAY) % MINUTES_IN_DAY] = true
                m++
            }
        }
        return mark
    }

    fun mergeMarkedMinutes(mark: BooleanArray): List<Window> {
        if (mark.size != MINUTES_IN_DAY) return emptyList()
        if (mark.all { it }) return listOf(Window(0, MINUTES_IN_DAY - 1))
        if (mark.none { it }) return emptyList()
        val runs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < MINUTES_IN_DAY) {
            if (!mark[i]) {
                i++
                continue
            }
            val start = i
            while (i < MINUTES_IN_DAY && mark[i]) i++
            runs.add(start to (i - 1))
        }
        if (runs.size >= 2 && runs.first().first == 0 && runs.last().second == MINUTES_IN_DAY - 1) {
            val wrap = Window(runs.last().first, runs.first().second)
            val mid = runs.subList(1, runs.lastIndex).map { Window(it.first, it.second) }
            return listOf(wrap) + mid
        }
        return runs.map { Window(it.first, it.second) }
    }

    fun weekdayAndMinute(ts: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        val wd = (cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return wd to minute
    }

    fun idleDelayMs(waitMs: Long): Long {
        if (waitMs <= 0L) return 0L
        return waitMs.coerceIn(IDLE_POLL_MIN_MS, IDLE_POLL_MAX_MS)
    }

    fun fmt(minuteOfDay: Int): String {
        val m = ((minuteOfDay % MINUTES_IN_DAY) + MINUTES_IN_DAY) % MINUTES_IN_DAY
        return "%02d:%02d".format(Locale.US, m / 60, m % 60)
    }
}
