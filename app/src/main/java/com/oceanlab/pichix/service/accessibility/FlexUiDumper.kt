package com.oceanlab.pichix.service.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.oceanlab.pichix.data.PichiFileLog

/**
 * Volcado de jerarquía UI Flex (Logcat tag PichiXDebug + fichero UI si está activo).
 */
object FlexUiDumper {

    const val DEBUG_TAG = "PichiXDebug"
    private const val MAX_NODES = 900
    private const val MAX_DEPTH = 45

    @Volatile private var lastDumpMs = 0L
    private const val THROTTLE_MS = 3000L

    fun maybeDump(root: AccessibilityNodeInfo?, packageName: String?) {
        if (root == null) return
        val now = System.currentTimeMillis()
        if (now - lastDumpMs < THROTTLE_MS) return
        lastDumpMs = now
        dumpHierarchy(root, packageName)
    }

    private var dumpUiNodeCount = 0

    fun dumpHierarchy(root: AccessibilityNodeInfo, packageName: String?) {
        dumpUiNodeCount = 0
        val header = "DUMP UI Flex (paquete: $packageName)"
        Log.d(DEBUG_TAG, "═══════════════════════════════════════════════════════")
        Log.d(DEBUG_TAG, header)
        PichiFileLog.ui(DEBUG_TAG, header)
        try {
            dumpNode(root, 0)
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "Error dump: ${e.message}")
            PichiFileLog.e(DEBUG_TAG, "dumpUiHierarchy", e, PichiFileLog.Channel.UI)
        }
        Log.d(DEBUG_TAG, "═══════════════════════════════════════════════════════")
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
        if (id.isNotEmpty() || txt.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            val b = Rect()
            node.getBoundsInScreen(b)
            val parts = mutableListOf(cls)
            if (id.isNotEmpty()) parts.add("id=$id")
            if (txt.isNotEmpty()) parts.add("text=\"${txt.take(80)}\"")
            if (desc.isNotEmpty()) parts.add("desc=\"${desc.take(80)}\"")
            if (b.width() > 0) parts.add("bounds=${b.left},${b.top}-${b.right},${b.bottom}")
            val line = "$indent${parts.joinToString(" · ")}$click"
            Log.d(DEBUG_TAG, line)
            PichiFileLog.ui(DEBUG_TAG, line)
        }
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
