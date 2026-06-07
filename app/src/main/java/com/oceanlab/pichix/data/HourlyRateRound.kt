package com.oceanlab.pichix.data

import kotlin.math.floor

/** Precio por hora agrupado: 20.50 → $20/h (truncado hacia abajo). */
object HourlyRateRound {

    fun rounded(rate: Double): Int = if (rate <= 0.0) 0 else floor(rate).toInt()

    fun label(rate: Double): String =
        if (rate <= 0.0) "—" else "$${rounded(rate)}/h"
}
