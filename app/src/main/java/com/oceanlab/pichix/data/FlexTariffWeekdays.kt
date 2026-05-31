package com.oceanlab.pichix.data

import java.util.Calendar

object FlexTariffWeekdays {
    val ORDERED: List<Pair<Int, String>> = listOf(
        Calendar.MONDAY to "Lun",
        Calendar.TUESDAY to "Mar",
        Calendar.WEDNESDAY to "Mié",
        Calendar.THURSDAY to "Jue",
        Calendar.FRIDAY to "Vie",
        Calendar.SATURDAY to "Sáb",
        Calendar.SUNDAY to "Dom",
    )

    fun label(day: Int): String = ORDERED.firstOrNull { it.first == day }?.second ?: "?"

    fun labelSet(days: Set<Int>): String {
        if (days.isEmpty()) return "—"
        val ordered = ORDERED.filter { it.first in days }.map { it.second }
        return when {
            ordered.size == ORDERED.size -> "Todos"
            ordered.size <= 4 -> ordered.joinToString(", ")
            else -> ordered.take(3).joinToString(", ") + " +${ordered.size - 3}"
        }
    }

    fun todayLabel(): String = label(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))
}
