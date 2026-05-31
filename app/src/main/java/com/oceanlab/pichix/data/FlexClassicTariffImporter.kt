package com.oceanlab.pichix.data

object FlexClassicTariffImporter {

    fun importFromClassic(settings: AppSettings): List<FlexTariffRule> {
        return listOf(
            FlexTariffRule(
                name = "Importada (criterios rápidos)",
                stationScope = FlexStationScope.GLOBAL,
                blockType = FlexBlockTypeFilter.ALL,
                payMode = FlexPayCriteriaMode.BLOCK_PAY,
                priceMin = settings.flexMinBlockPay.toDouble(),
                minHourlyRate = settings.flexMinHourlyRate.toDouble(),
                blockStartFilterEnabled = true,
                blockStartFromMinutes = settings.flexMinStartHour * 60,
                blockStartToMinutes = 23 * 60 + 59,
            ),
        )
    }

    fun summaryMessage(count: Int): String =
        "Se importó $count regla desde los criterios rápidos (mín. bloque, $/h, hora inicio)."
}
