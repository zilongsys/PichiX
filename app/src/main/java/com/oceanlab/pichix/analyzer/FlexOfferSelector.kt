package com.oceanlab.pichix.analyzer

import com.oceanlab.pichix.data.AppSettings

object FlexOfferSelector {

    data class EvaluatedOffer(val offer: FlexBlockOffer, val result: FlexGrabResult)

    fun pickAcceptable(
        evaluated: List<EvaluatedOffer>,
        settings: AppSettings,
    ): FlexBlockOffer? {
        val acceptable = evaluated.filter {
            it.result == FlexGrabResult.ACCEPT || it.result == FlexGrabResult.SIMULATED_ACCEPT
        }
        if (acceptable.isEmpty()) return null
        if (settings.flexOfferPickMode == AppSettings.OFFER_PICK_FIRST) {
            return acceptable.first().offer
        }
        return acceptable.maxWithOrNull(compareByCriterion(settings.flexOfferRankCriterion))?.offer
    }

    fun selectionSummary(
        winner: FlexBlockOffer,
        evaluated: List<EvaluatedOffer>,
        settings: AppSettings,
    ): String {
        val acceptable = evaluated.count {
            it.result == FlexGrabResult.ACCEPT || it.result == FlexGrabResult.SIMULATED_ACCEPT
        }
        val hourly = winner.hourlyRate?.let { "%.1f".format(it) } ?: "?"
        val base = "${winner.stationText} ${winner.payText} ($hourly \$/h)"
        return if (acceptable > 1 && settings.usesBestOfferPick()) {
            "Mejor (${settings.offerRankLabel()}): $base · $acceptable válidas"
        } else {
            "Tomando: $base"
        }
    }

    private fun compareByCriterion(criterion: String): Comparator<EvaluatedOffer> {
        return Comparator { a, b ->
            val av = metric(a.offer, criterion)
            val bv = metric(b.offer, criterion)
            when {
                av == bv -> a.offer.index.compareTo(b.offer.index)
                criterion == AppSettings.OFFER_RANK_START_SOONEST ->
                    av.compareTo(bv)
                criterion == AppSettings.OFFER_RANK_DURATION_MIN ->
                    av.compareTo(bv)
                else -> bv.compareTo(av)
            }
        }
    }

    private fun metric(offer: FlexBlockOffer, criterion: String): Double = when (criterion) {
        AppSettings.OFFER_RANK_BLOCK_PAY ->
            offer.payAmount ?: FlexGrabberEvaluator.parsePay(offer.payText) ?: 0.0
        AppSettings.OFFER_RANK_DURATION_MIN,
        AppSettings.OFFER_RANK_DURATION_MAX ->
            offer.durationHours ?: 0.0
        AppSettings.OFFER_RANK_START_SOONEST ->
            FlexGrabberEvaluator.minutesUntilBlockStart(offer.timeText)?.toDouble() ?: Double.MAX_VALUE
        else ->
            offer.hourlyRate ?: run {
                val pay = offer.payAmount ?: FlexGrabberEvaluator.parsePay(offer.payText) ?: return@run 0.0
                val hours = offer.durationHours ?: return@run 0.0
                if (hours > 0.1) pay / hours else 0.0
            }
    }
}
