package com.oceanlab.pichix.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.oceanlab.pichix.data.MonitorPackages

/**
 * Obtiene la raíz del árbol de Flex sin confundirla con overlay de PichiX u otras ventanas.
 */
object FlexWindowRoots {

    fun isTargetInForeground(service: AccessibilityService): Boolean =
        obtainTargetRoot(service, requireActiveWindow = true) != null

    /**
     * @param requireActiveWindow si true, solo ventana activa de Flex (primer plano real).
     */
    fun obtainTargetRoot(
        service: AccessibilityService,
        requireActiveWindow: Boolean,
    ): AccessibilityNodeInfo? {
        val target = MonitorPackages.primaryTarget(service) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            findInWindows(service.windows, target, activeOnly = true)?.let { return it }
            if (!requireActiveWindow) {
                findInWindows(service.windows, target, activeOnly = false)?.let { return it }
            }
        }
        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() == target) {
            return active
        }
        active?.recycle()
        return null
    }

    private fun findInWindows(
        windows: List<AccessibilityWindowInfo>?,
        target: String,
        activeOnly: Boolean,
    ): AccessibilityNodeInfo? {
        if (windows.isNullOrEmpty()) return null
        for (window in windows) {
            if (activeOnly && !window.isActive) continue
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() == target) {
                    return AccessibilityNodeInfo.obtain(root)
                }
            } finally {
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }
}
