package com.oceanlab.pichix.analyzer

/**
 * Comprueba que la pantalla de detalle corresponde a la fila de lista que se intentó tomar.
 */
object OfferListDetailMatcher {

    data class FieldMismatch(
        val field: String,
        val listValue: String,
        val detailValue: String,
    )

    private val payTolerance = 0.51

    private val unavailablePhrases = listOf(
        "ya no está disponible",
        "ya no esta disponible",
        "no longer available",
        "block unavailable",
        "offer unavailable",
        "this block is no longer",
        "this offer is no longer",
    )

    fun unavailablePhrasesForLog(): List<String> = unavailablePhrases

    fun isBlockUnavailable(screenText: String): Boolean {
        val lower = screenText.lowercase()
        return unavailablePhrases.any { lower.contains(it) }
    }

    fun verify(offer: FlexBlockOffer, details: Map<String, String>): List<FieldMismatch> {
        val pay = payMismatch(offer, details)
        val soft = softMismatches(offer, details)
        return buildList {
            pay?.let { add(it) }
            addAll(soft)
        }
    }

    /** Desajuste crítico (pago distinto) — dispara la acción configurada. */
    fun payMismatch(offer: FlexBlockOffer, details: Map<String, String>): FieldMismatch? {
        val listPay = offer.payAmount ?: FlexGrabberEvaluator.parsePay(offer.payText)
        val detailPay = FlexGrabberEvaluator.parsePay(details["pay_range"].orEmpty())
        if (listPay != null && detailPay != null) {
            if (kotlin.math.abs(listPay - detailPay) > payTolerance) {
                return FieldMismatch(
                    "Pago",
                    "\$${"%.2f".format(listPay)}",
                    "\$${"%.2f".format(detailPay)}",
                )
            }
        } else if (listPay != null && detailPay == null && details["pay_range"].orEmpty().isNotBlank()) {
            return FieldMismatch(
                "Pago",
                "\$${"%.2f".format(listPay)}",
                details["pay_range"].orEmpty().take(40),
            )
        }
        return null
    }

    /** Estación u horario distintos (aviso; solos no cancelan si el pago coincide). */
    fun softMismatches(offer: FlexBlockOffer, details: Map<String, String>): List<FieldMismatch> {
        val mismatches = mutableListOf<FieldMismatch>()
        val listStation = offer.stationText.trim()
        val detailStation = details["station"].orEmpty().trim()
        if (listStation.isNotBlank() && detailStation.isNotBlank() && !stationsMatch(listStation, detailStation)) {
            mismatches.add(FieldMismatch("Estación", listStation, detailStation))
        }

        val listTime = offer.timeText.trim()
        val detailTime = details["time_window"].orEmpty().trim()
        if (listTime.isNotBlank() && detailTime.isNotBlank() && !timesRoughlyMatch(listTime, detailTime)) {
            mismatches.add(FieldMismatch("Horario", listTime, detailTime))
        }
        return mismatches
    }

    /** ¿Ejecutar sonido/cancelar? Solo pago distinto o estación+horario a la vez. */
    fun shouldTriggerMismatchAction(offer: FlexBlockOffer, details: Map<String, String>): Boolean {
        if (payMismatch(offer, details) != null) return true
        val soft = softMismatches(offer, details)
        return soft.size >= 2
    }

    fun formatReason(mismatches: List<FieldMismatch>): String =
        "Detalle no coincide: " + mismatches.joinToString(" · ") { m ->
            "${m.field} lista=${m.listValue} detalle=${m.detailValue}"
        }

    private fun normalizeStation(s: String): String =
        s.lowercase().replace(Regex("""[^\p{L}\p{N}\s]"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun stationsMatch(a: String, b: String): Boolean {
        val na = normalizeStation(a)
        val nb = normalizeStation(b)
        if (na.isBlank() || nb.isBlank()) return true
        if (na == nb) return true
        if (na.contains(nb) || nb.contains(na)) return true
        val naTokens = na.split(' ').filter { it.length > 2 }.toSet()
        val nbTokens = nb.split(' ').filter { it.length > 2 }.toSet()
        if (naTokens.isEmpty() || nbTokens.isEmpty()) return false
        val overlap = naTokens.intersect(nbTokens).size
        return overlap >= minOf(naTokens.size, nbTokens.size) / 2
    }

    private fun timesRoughlyMatch(listTime: String, detailTime: String): Boolean {
        val listStart = FlexGrabberEvaluator.parseStartMinutesOfDay(listTime)
        val detailStart = FlexGrabberEvaluator.parseStartMinutesOfDay(detailTime)
        if (listStart != null && detailStart != null) {
            val diff = kotlin.math.abs(listStart - detailStart)
            if (diff <= 15 || diff >= 24 * 60 - 15) return true
        }
        val a = listTime.lowercase().replace(Regex("""\s+"""), "")
        val b = detailTime.lowercase().replace(Regex("""\s+"""), "")
        if (a == b) return true
        if (a.length > 6 && b.length > 6 && (a.contains(b) || b.contains(a))) return true
        return false
    }
}
