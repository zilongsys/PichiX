package com.oceanlab.pichix.service.accessibility

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityNodeUtils {

    fun recycleNodes(nodes: Iterable<AccessibilityNodeInfo>?) {
        nodes?.forEach { n ->
            try { n.recycle() } catch (_: Exception) {}
        }
    }

    fun AccessibilityNodeInfo.hasViewId(fullId: String): Boolean =
        useViewIdNodes(fullId) { it.isNotEmpty() }

    fun <T> AccessibilityNodeInfo.useViewIdNodes(
        fullId: String,
        block: (List<AccessibilityNodeInfo>) -> T,
    ): T {
        val nodes = findAccessibilityNodeInfosByViewId(fullId)
        return try {
            block(nodes ?: emptyList())
        } finally {
            recycleNodes(nodes)
        }
    }

    fun AccessibilityNodeInfo.firstTextByViewId(fullId: String): String? =
        useViewIdNodes(fullId) { list ->
            list.firstOrNull()?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }

    fun AccessibilityNodeInfo.allTextsByViewId(fullId: String): List<String> =
        useViewIdNodes(fullId) { list ->
            list.mapNotNull { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        }

    fun AccessibilityNodeInfo.performClickOnClickableSelfOrAncestor(maxDepth: Int = 16): Boolean {
        var cur: AccessibilityNodeInfo? = this
        var depth = 0
        while (cur != null && depth < maxDepth) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    /** Coincidencia exacta del texto visible (no parcial). */
    fun AccessibilityNodeInfo.findClickableByExactText(
        text: String,
        ignoreCase: Boolean = false,
    ): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        var found: AccessibilityNodeInfo? = null
        withAllObtainedNodes { nodes ->
            for (n in nodes) {
                val t = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                if (t.equals(text, ignoreCase)) {
                    var cur: AccessibilityNodeInfo? = n
                    while (cur != null) {
                        if (cur.isClickable) {
                            found = AccessibilityNodeInfo.obtain(cur)
                            break
                        }
                        cur = cur.parent
                    }
                    if (found != null) break
                }
            }
        }
        return found
    }

    fun AccessibilityNodeInfo.findClickableByText(text: String, ignoreCase: Boolean = true): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        withAllObtainedNodes { nodes ->
            for (n in nodes) {
                val t = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                if (t.equals(text, ignoreCase) || t.contains(text, ignoreCase)) {
                    var cur: AccessibilityNodeInfo? = n
                    while (cur != null) {
                        if (cur.isClickable) {
                            found = AccessibilityNodeInfo.obtain(cur)
                            break
                        }
                        cur = cur.parent
                    }
                    if (found != null) break
                }
            }
        }
        return found
    }

    fun <R> AccessibilityNodeInfo.withAllObtainedNodes(block: (List<AccessibilityNodeInfo>) -> R): R {
        val obtained = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            obtained.add(AccessibilityNodeInfo.obtain(n))
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
                try { c.recycle() } catch (_: Exception) {}
            }
        }
        walk(this)
        return try {
            block(obtained)
        } finally {
            recycleNodes(obtained)
        }
    }

    fun AccessibilityNodeInfo.getAllText(): String = getAllVisibleText()

    /**
     * Todo el texto dibujado accesible en el árbol (incluye banners/snackbars flotantes
     * sobre la lista, no solo ventanas modales).
     */
    fun AccessibilityNodeInfo.getAllVisibleText(): String {
        val sb = StringBuilder()
        val seen = linkedSetOf<String>()
        withAllObtainedNodes { nodes ->
            for (n in nodes) {
                for (part in nodeDrawnTextParts(n)) {
                    if (!seen.add(part)) continue
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(part)
                }
            }
        }
        return sb.toString()
    }

    private fun nodeDrawnTextParts(node: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        fun add(raw: CharSequence?) {
            val part = raw?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
            out.add(part)
        }
        add(node.text)
        add(node.contentDescription)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(node.hintText)
            add(node.tooltipText)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(node.paneTitle)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(node.stateDescription)
        }
        return out
    }
}
