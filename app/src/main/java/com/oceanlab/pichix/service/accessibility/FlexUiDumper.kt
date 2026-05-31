package com.oceanlab.pichix.service.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.oceanlab.pichix.data.PichiFileLog
import com.oceanlab.pichix.service.accessibility.AccessibilityNodeUtils.getAllVisibleText

/**
 * Volcado de jerarquía UI Flex (Logcat tag PichiXDebug + fichero UI si está activo).
 */
object FlexUiDumper {

    const val DEBUG_TAG = "PichiXDebug"
    private const val MAX_NODES = 1200
    private const val MAX_DEPTH = 50
    private const val MAX_SCREEN_CHARS_LOG = 8000

    @Volatile private var lastPeriodicDumpMs = 0L
    private const val PERIODIC_THROTTLE_MS = 3000L

    /** Volcado periódico (eventos / tick): máximo cada ~3 s. */
    fun maybeDumpPeriodic(root: AccessibilityNodeInfo?, packageName: String?) {
        if (root == null) return
        val now = System.currentTimeMillis()
        if (now - lastPeriodicDumpMs < PERIODIC_THROTTLE_MS) return
        lastPeriodicDumpMs = now
        dumpHierarchy(root, packageName, reason = "periodico")
    }

    /** Cada clic en Refresh: sin throttle, siempre lee y vuelca toda la pantalla. */
    fun dumpOnRefreshClick(root: AccessibilityNodeInfo?, packageName: String?, phase: String) {
        if (root == null) return
        dumpHierarchy(root, packageName, reason = "refresh_$phase")
    }

    private var dumpUiNodeCount = 0

    fun dumpHierarchy(
        root: AccessibilityNodeInfo,
        packageName: String?,
        reason: String = "manual",
    ) {
        dumpUiNodeCount = 0
        val fullText = root.getAllVisibleText()
        val header = "DUMP UI Flex [$reason] (paquete: $packageName)"
        Log.i(DEBUG_TAG, "═══════════════════════════════════════════════════════")
        Log.i(DEBUG_TAG, header)
        Log.i(DEBUG_TAG, "TEXTO_PANTALLA (${fullText.length} chars): ${fullText.take(MAX_SCREEN_CHARS_LOG)}")
        PichiFileLog.ui(DEBUG_TAG, header)
        PichiFileLog.ui(DEBUG_TAG, "TEXTO_PANTALLA (${fullText.length}): ${fullText.take(MAX_SCREEN_CHARS_LOG)}")
        try {
            dumpNode(root, 0)
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "Error dump: ${e.message}")
            PichiFileLog.e(DEBUG_TAG, "dumpUiHierarchy", e, PichiFileLog.Channel.UI)
        }
        Log.i(DEBUG_TAG, "Fin volcado: $dumpUiNodeCount nodos · pantalla ${fullText.length} chars")
        Log.i(DEBUG_TAG, "═══════════════════════════════════════════════════════")
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > MAX_DEPTH || dumpUiNodeCount >= MAX_NODES) return
        dumpUiNodeCount++
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName.orEmpty()
        val txt = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val click = if (node.isClickable) " [CLICK]" else ""
        val b = Rect()
        node.getBoundsInScreen(b)
        val parts = mutableListOf(cls)
        if (id.isNotEmpty()) parts.add("id=$id")
        if (txt.isNotEmpty()) parts.add("text=\"${txt.take(80)}\"")
        if (desc.isNotEmpty()) parts.add("desc=\"${desc.take(80)}\"")
        if (b.width() > 0 && b.height() > 0) {
            parts.add("bounds=${b.left},${b.top}-${b.right},${b.bottom}")
        }
        val line = "$indent${parts.joinToString(" · ")}$click"
        Log.i(DEBUG_TAG, line)
        PichiFileLog.ui(DEBUG_TAG, line)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                dumpNode(child, depth + 1)
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }
}
