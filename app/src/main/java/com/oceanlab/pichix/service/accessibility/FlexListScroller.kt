package com.oceanlab.pichix.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.oceanlab.pichix.flex.FlexIds
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.useViewIdNodes
import java.util.Random

/**
 * Scroll en la lista de ofertas Flex (principio MakiX/Spark v71):
 * gesto corto con X e inicio/fin aleatorios dentro de la zona del [FlexIds.LIST_RECYCLER],
 * sin tocar el footer (Refresh / botones).
 */
class FlexListScroller(
    private val service: AccessibilityService,
    private val reader: FlexScreenReader,
) {

    data class SwipeSpec(val cx: Float, val yStart: Float, val yEnd: Float, val durationMs: Long)

    fun scrollInOfferZone(scrollingDown: Boolean, onFinished: (dispatched: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onFinished(performAccessibilityScroll(scrollingDown))
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            onFinished(false)
            return
        }
        try {
            val zone = computeScrollZone(root)
            if (zone == null) {
                Log.w(TAG, "↕ Sin zona de lista — ACTION_SCROLL")
                onFinished(performAccessibilityScroll(scrollingDown))
                return
            }
            val (top, bottom) = zone
            if (bottom - top < dp(120)) {
                Log.w(TAG, "↕ Zona muy baja — ACTION_SCROLL")
                onFinished(performAccessibilityScroll(scrollingDown))
                return
            }
            val spec = swipePointsInZone(top, bottom, scrollingDown, Random())
            Log.d(
                TAG,
                "↕ Gesto ${if (scrollingDown) "↓" else "↑"}: x=${spec.cx.toInt()} " +
                    "y=${spec.yStart.toInt()}→${spec.yEnd.toInt()} ${spec.durationMs}ms " +
                    "zona=[${top.toInt()}-${bottom.toInt()}]",
            )
            dispatchSwipeGesture(spec, onFinished, root, scrollingDown)
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** Zona segura top..bottom en px de pantalla, o null si no hay lista. */
    private fun computeScrollZone(root: AccessibilityNodeInfo): Pair<Float, Float>? {
        val dm = service.resources.displayMetrics
        val h = dm.heightPixels.toFloat()
        val edge = dp(12)
        val bottomInset = dp(36)

        val recyclerId = reader.resolveId(FlexIds.LIST_RECYCLER)
        if (recyclerId != null) {
            val fromRecycler = root.useViewIdNodes(recyclerId) { nodes ->
                val node = nodes.firstOrNull() ?: return@useViewIdNodes null
                val rb = Rect()
                node.getBoundsInScreen(rb)
                if (rb.height() <= dp(120)) return@useViewIdNodes null
                val top = rb.top.toFloat() + edge
                val bottom = capSwipeBottom(
                    safeBottomForRoot(root, rb.bottom.toFloat() - bottomInset),
                    top,
                )
                if (bottom > top + dp(80)) top to bottom else null
            }
            if (fromRecycler != null) return fromRecycler
        }

        val primaryId = reader.resolveId(FlexIds.PRIMARY_BUTTON)
        var footerTop = h * 0.88f
        if (primaryId != null) {
            root.useViewIdNodes(primaryId) { nodes ->
                nodes.forEach { n ->
                    val b = Rect()
                    n.getBoundsInScreen(b)
                    if (b.top in 1..h.toInt()) footerTop = minOf(footerTop, b.top.toFloat() - dp(72))
                }
            }
        }

        val top = h * 0.28f
        val bottom = capSwipeBottom(minOf(footerTop, h - dp(48)), top)
        return if (bottom > top + dp(80)) top to bottom else null
    }

    private fun safeBottomForRoot(root: AccessibilityNodeInfo, cap: Float): Float {
        val primaryId = reader.resolveId(FlexIds.PRIMARY_BUTTON) ?: return cap
        var footerTop = cap
        root.useViewIdNodes(primaryId) { nodes ->
            nodes.forEach { n ->
                val b = Rect()
                n.getBoundsInScreen(b)
                if (b.top > 0) footerTop = minOf(footerTop, b.top.toFloat() - dp(16))
            }
        }
        return footerTop
    }

    private fun capSwipeBottom(bottom: Float, top: Float): Float =
        bottom.coerceAtLeast(top + dp(160))

    /**
     * Swipe corto centrado en la zona (MakiX [swipePointsInZone]): X 30–70% ancho,
     * longitud 25–40% de la altura de zona.
     */
    private fun swipePointsInZone(
        zoneTop: Float,
        zoneBottom: Float,
        scrollingDown: Boolean,
        rnd: Random,
    ): SwipeSpec {
        val w = service.resources.displayMetrics.widthPixels
        val cx = w * (0.3f + rnd.nextFloat() * 0.4f)
        val zoneHeight = (zoneBottom - zoneTop).coerceAtLeast(200f)
        val swipeFrac = 0.25f + rnd.nextFloat() * 0.15f
        val swipeLen = zoneHeight * swipeFrac
        val center = zoneTop + zoneHeight * 0.5f
        val yStart = if (scrollingDown) {
            (center + swipeLen / 2f).coerceAtMost(zoneBottom - dp(20))
        } else {
            (center - swipeLen / 2f).coerceAtLeast(zoneTop + dp(20))
        }
        val yEnd = if (scrollingDown) {
            (center - swipeLen / 2f).coerceAtLeast(zoneTop + dp(20))
        } else {
            (center + swipeLen / 2f).coerceAtMost(zoneBottom - dp(20))
        }
        val dur = 80L + (rnd.nextFloat() * 60f).toLong()
        return SwipeSpec(cx, yStart, yEnd, dur)
    }

    private fun dispatchSwipeGesture(
        spec: SwipeSpec,
        onFinished: (Boolean) -> Unit,
        root: AccessibilityNodeInfo,
        scrollingDown: Boolean,
    ) {
        val path = Path().apply {
            moveTo(spec.cx, spec.yStart)
            lineTo(spec.cx, spec.yEnd)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, spec.durationMs))
            .build()
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onFinished(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onFinished(performAccessibilityScroll(scrollingDown))
                }
            },
            null,
        )
        if (!dispatched) {
            onFinished(performAccessibilityScroll(scrollingDown))
        }
    }

    private fun performAccessibilityScroll(scrollingDown: Boolean): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return try {
            val action = if (scrollingDown) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            val recyclerId = reader.resolveId(FlexIds.LIST_RECYCLER) ?: return false
            var ok = false
            root.useViewIdNodes(recyclerId) { nodes ->
                nodes.firstOrNull()?.let { node ->
                    ok = node.performAction(action)
                }
            }
            ok
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun dp(v: Int): Float =
        v * service.resources.displayMetrics.density

    companion object {
        private const val TAG = "PichiXScroll"
        const val SETTLE_MS = 480L
    }
}
