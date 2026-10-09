package com.oceanlab.pichix.ui

import java.util.Locale
import kotlin.math.round

object CalendarUi {

    enum class GoalLevel { MET, HALF, LOW }

    /** Redondeo a entero y formato compacto (máx. ~4 caracteres) para celdas del calendario. */
    fun formatCellEarned(amount: Double): String {
        val n = round(amount).toInt()
        if (n <= 0) return "—"
        return when {
            n >= 10_000 -> "${n / 1000}k"
            n >= 1000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
            else -> "$$n"
        }
    }

    fun meetsDailyGoal(earned: Double, dailyMin: Double): Boolean =
        dailyMin > 0.0 && earned >= dailyMin - 0.01

    fun hasEarned(earned: Double): Boolean = round(earned).toInt() > 0

    /** Verde = meta completa; ámbar = al menos 50%; rojo = menos del 50% (solo si hubo ganancias). */
    fun goalLevel(earned: Double, goal: Double): GoalLevel? {
        if (goal <= 0.0 || !hasEarned(earned)) return null
        return when {
            earned >= goal - 0.01 -> GoalLevel.MET
            earned >= goal * 0.5 - 0.01 -> GoalLevel.HALF
            else -> GoalLevel.LOW
        }
    }

    fun goalStatusLabel(level: GoalLevel?): String = when (level) {
        GoalLevel.MET -> "Cumplida"
        GoalLevel.HALF -> "Mitad cumplida"
        GoalLevel.LOW -> "Bajo meta"
        null -> "Pendiente"
    }
}
