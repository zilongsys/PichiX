package com.oceanlab.pichix.analyzer

import com.oceanlab.pichix.data.FlexPayCriteriaMode
import com.oceanlab.pichix.data.FlexTariffRule
import kotlin.math.abs

/**
 * Comprobaciones puras de una regla de tarifa (sin Android ni reloj), extraídas de
 * [FlexTariffEvaluator] para poder probarlas. El comportamiento es idéntico al original.
 */
object TariffMath {

    fun matchesPay(pay: Double, hourly: Double, rule: FlexTariffRule): Boolean =
        when (rule.payMode) {
            FlexPayCriteriaMode.BLOCK_PAY -> payInBlockRange(pay, rule)
            // Solo $/h: otras reglas (p. ej. por bloque) se evalúan por orden de prioridad.
            FlexPayCriteriaMode.HOURLY_PAY -> hourly >= rule.minHourlyRate
            // Misma regla exige las dos condiciones.
            FlexPayCriteriaMode.HOURLY_AND_BLOCK -> hourly >= rule.minHourlyRate && payInBlockRange(pay, rule)
            FlexPayCriteriaMode.MANUAL_FIXED -> abs(pay - rule.priceMin) < 0.51
            FlexPayCriteriaMode.MANUAL_ANY -> true
        }

    private fun payInBlockRange(pay: Double, rule: FlexTariffRule): Boolean {
        if (pay < rule.priceMin) return false
        val max = rule.priceMax
        if (max != null && pay > max) return false
        return true
    }

    /** Duración desconocida (null) siempre pasa; límites inclusivos. */
    fun matchesDuration(durationHours: Double?, rule: FlexTariffRule): Boolean {
        if (durationHours == null) return true
        rule.minDurationHours?.let { if (durationHours < it) return false }
        rule.maxDurationHours?.let { if (durationHours > it) return false }
        return true
    }

    /** `value` dentro de [from, to] en minutos del día; si from > to la ventana cruza medianoche. */
    fun inWindow(from: Int, to: Int, value: Int): Boolean =
        if (from <= to) value in from..to else value >= from || value <= to

    /** `dayOfWeek` con la convención de `Calendar.DAY_OF_WEEK` (domingo = 1). */
    fun weekdayAllowed(rule: FlexTariffRule, dayOfWeek: Int): Boolean {
        if (!rule.weekdaysEnabled || rule.allowedWeekdays.isEmpty()) return true
        return dayOfWeek in rule.allowedWeekdays
    }
}
