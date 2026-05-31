package com.oceanlab.pichix.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.accessibilityservice.GestureDescription
import android.os.Build
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo
import com.oceanlab.pichix.analyzer.FlexBlockOffer
import com.oceanlab.pichix.analyzer.FlexGrabberEvaluator
import com.oceanlab.pichix.data.FlexState
import com.oceanlab.pichix.flex.FlexIds
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.allTextsByViewId
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.findClickableByText
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.firstTextByViewId
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.getAllText
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.hasViewId
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.recycleNodes
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.useViewIdNodes

class FlexScreenReader(private val service: AccessibilityService) {

    private val appPackage: String
        get() = com.oceanlab.pichix.data.MonitorPackages.primaryTarget(service)
            ?: "com.amazon.rabbit"

    fun resolveId(suffix: String): String? {
        for (cand in FlexIds.viewIdCandidates(suffix, appPackage)) {
            val root = service.rootInActiveWindow ?: continue
            try {
                if (root.hasViewId(cand)) return cand
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
        return FlexIds.viewIdCandidates(suffix, appPackage).firstOrNull()
    }

    fun readFullScreenText(): String {
        val root = service.rootInActiveWindow ?: return ""
        return try {
            root.getAllText()
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun detectScreenFlags(screenText: String): ScreenFlags {
        val lower = screenText.lowercase()
        return ScreenFlags(
            blockUnavailable = lower.contains("no longer available") ||
                lower.contains("not available") ||
                lower.contains("unavailable"),
            captcha = lower.contains("captcha") || lower.contains("robot") ||
                lower.contains("puzzle") || lower.contains("verify"),
            offerScheduled = lower.contains("scheduled") && lower.contains("offer"),
            onOffersList = lower.contains("offer") || hasAnyOfferPay(),
        )
    }

    private fun hasAnyOfferPay(): Boolean {
        val id = resolveId(FlexIds.OFFER_PAY) ?: return false
        val root = service.rootInActiveWindow ?: return false
        return try {
            root.hasViewId(id)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun readOffersFromList(): List<FlexBlockOffer> {
        val payId = resolveId(FlexIds.OFFER_PAY) ?: return emptyList()
        val timeId = resolveId(FlexIds.OFFER_TIME)
        val stationId = resolveId(FlexIds.LEFT_SECONDARY_LABEL)
            ?: resolveId(FlexIds.OFFER_STATION)

        val root = service.rootInActiveWindow ?: return emptyList()
        return try {
            root.useViewIdNodes(payId) { payNodes ->
                payNodes.mapIndexed { index, payNode ->
                    val payText = payNode.text?.toString()?.trim().orEmpty()
                    val pay = FlexGrabberEvaluator.parsePay(payText)
                    val timeText = findSiblingText(payNode, timeId, index)
                    val stationText = findSiblingText(payNode, stationId, index)
                    val hourly = pay?.let { FlexGrabberEvaluator.hourlyFromPayAndTime(it, timeText) }
                    FlexBlockOffer(
                        index = index,
                        payText = payText,
                        timeText = timeText,
                        stationText = stationText,
                        payAmount = pay,
                        startHour = FlexGrabberEvaluator.parseStartHour(timeText),
                        durationHours = FlexGrabberEvaluator.parseDurationHours(timeText),
                        hourlyRate = hourly,
                    ).also { offer ->
                        FlexState.putOfferField("$payId#$index", payText)
                        timeId?.let { FlexState.putOfferField("$it#$index", timeText) }
                        stationId?.let { FlexState.putOfferField("$it#$index", stationText) }
                    }
                }
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun findSiblingText(anchor: AccessibilityNodeInfo, fullId: String?, index: Int): String {
        if (fullId == null) return ""
        val root = service.rootInActiveWindow ?: return ""
        return try {
            val texts = root.allTextsByViewId(fullId)
            texts.getOrElse(index) { texts.firstOrNull().orEmpty() }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun readBlockDetails(): Map<String, String> {
        FlexState.clearBlockDetails()
        val fields = mapOf(
            FlexIds.OFFER_DETAILS_STATION to "station",
            FlexIds.PAY_RANGE_WITH_TIPS to "pay_range",
            FlexIds.OFFER_TIME_WINDOW to "time_window",
            FlexIds.OFFER_DATE to "date",
        )
        val root = service.rootInActiveWindow ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        return try {
            for ((suffix, key) in fields) {
                val id = resolveId(suffix) ?: continue
                val text = root.firstTextByViewId(id).orEmpty()
                if (text.isNotEmpty()) {
                    out[key] = text
                    FlexState.putBlockDetail(id, text)
                }
            }
            out
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun clickScheduleOnList(index: Int = 0): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return try {
            val node = root.findClickableByText("Schedule")
                ?: root.findClickableByText("Accept")
            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                try { node.recycle() } catch (_: Exception) {}
                ok
            } else {
                clickMeridianButton(index)
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun clickScheduleOnDetail(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return try {
            val id = resolveId(FlexIds.MERIDIAN_BUTTON_TEXT) ?: return false
            root.useViewIdNodes(id) { nodes ->
                val schedule = nodes.firstOrNull { n ->
                    val t = n.text?.toString().orEmpty()
                    t.contains("Schedule", ignoreCase = true)
                } ?: nodes.firstOrNull()
                schedule?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun clickMeridianButton(index: Int): Boolean {
        val id = resolveId(FlexIds.MERIDIAN_BUTTON_TEXT) ?: return false
        val root = service.rootInActiveWindow ?: return false
        return try {
            root.useViewIdNodes(id) { nodes ->
                val target = nodes.getOrNull(index) ?: nodes.firstOrNull()
                target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun clickRefresh(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return try {
            val node = root.findClickableByText("Refresh")
            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                try { node.recycle() } catch (_: Exception) {}
                ok
            } else false
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    fun clickBack(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun offerListSignature(): String {
        return readOffersFromList().joinToString("|") { o ->
            "${o.payText}:${o.timeText}:${o.stationText}"
        }
    }

    fun scrollDown(): Boolean = scrollGesture(down = true)

    fun scrollUp(): Boolean = scrollGesture(down = false)

    private fun scrollGesture(down: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val metrics: DisplayMetrics = service.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(w * 0.5f, if (down) h * 0.72f else h * 0.28f)
            lineTo(w * 0.5f, if (down) h * 0.28f else h * 0.72f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 280)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    data class ScreenFlags(
        val blockUnavailable: Boolean = false,
        val captcha: Boolean = false,
        val offerScheduled: Boolean = false,
        val onOffersList: Boolean = false,
    )
}
