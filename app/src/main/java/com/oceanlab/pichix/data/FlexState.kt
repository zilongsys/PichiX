package com.oceanlab.pichix.data

import org.json.JSONObject

/**
 * Estado en memoria equivalente a las variables globales gv_FLX-* de MacroDroid.
 */
object FlexState {
    var flexTimerSec: Int = 2
    var counterPrev: Long = 0L
    var counter: Int = 0
    var counterScroll: Int = 0
    var scrollNeeded: Boolean = false
    var key1: Boolean = false
    var key2: Boolean = false
    var key3: Boolean = false
    var timeStr: String = ""

    val offersDic = linkedMapOf<String, String>()
    val blockDetailsDic = linkedMapOf<String, String>()

    fun resetScrollCycle() {
        counterScroll = 0
        scrollNeeded = false
    }

    fun putOfferField(key: String, value: String) {
        if (value.isNotBlank()) offersDic[key] = value
    }

    fun putBlockDetail(key: String, value: String) {
        if (value.isNotBlank()) blockDetailsDic[key] = value
    }

    fun clearBlockDetails() = blockDetailsDic.clear()

    fun snapshotOffersJson(): String {
        val o = JSONObject()
        offersDic.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }
}
