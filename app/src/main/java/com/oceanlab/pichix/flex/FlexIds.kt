package com.oceanlab.pichix.flex

/**
 * IDs de vistas Amazon Flex (Rabbit) usados en FLEX.category / Terminator-Grabber.
 * Se prueban con [com.amazon.flex.rabbit] y el paquete de la app instalada.
 */
object FlexIds {
    const val FLEX_PKG_RESOURCE = "com.amazon.flex.rabbit"

    const val OFFER_PAY = "offer_pay"
    const val OFFER_TIME = "offer_time"
    const val OFFER_STATION = "offer_station"
    const val LEFT_SECONDARY_LABEL = "left_secondary_label"
    const val OFFER_DETAILS_STATION = "offer_details_station"
    const val PAY_RANGE_WITH_TIPS = "pay_range_with_tips"
    const val OFFER_TIME_WINDOW = "offer_time_window"
    const val OFFER_DATE = "offer_date"
    const val MERIDIAN_BUTTON_TEXT = "meridian_button_text_view"
    const val MERIDIAN_TAB_ITEM_LABEL = "meridian_tab_item_label"
    const val PRIMARY_BUTTON = "primaryButton"
    const val OFFER_CARD = "card"
    const val LIST_RECYCLER = "list_recycler"
    const val FILTER_OFFER_COUNT = "filter_offer_count"

    val ALL_SUFFIXES = listOf(
        OFFER_PAY, OFFER_TIME, OFFER_STATION, LEFT_SECONDARY_LABEL,
        OFFER_DETAILS_STATION, PAY_RANGE_WITH_TIPS, OFFER_TIME_WINDOW,
        OFFER_DATE, MERIDIAN_BUTTON_TEXT,
    )

    fun viewIdCandidates(suffix: String, appPackage: String): List<String> = listOf(
        "$FLEX_PKG_RESOURCE:id/$suffix",
        "$appPackage:id/$suffix",
    )
}
