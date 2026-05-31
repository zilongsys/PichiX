package com.oceanlab.pichix.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.oceanlab.pichix.data.MonitorPackages

/**
 * Resuelve el árbol de accesibilidad de Amazon Flex sin confundirlo con PichiX/overlay.
 *
 * En muchos dispositivos [AccessibilityWindowInfo.isActive] no marca Flex aunque esté visible;
 * por eso se prueba activa → con foco → cualquier ventana del paquete objetivo → rootInActiveWindow.
 */
object FlexWindowRoots {

    private const val TAG = "FlexWindowRoots"

    /** Flex visible e interactuable (no el usuario dentro de la app PichiX a pantalla completa). */
    fun isFlexAvailableForBot(service: AccessibilityService): Boolean {
        val target = MonitorPackages.primaryTarget(service) ?: return false
        if (isFullScreenPichix(service)) return false
        return obtainFlexTreeRoot(service) != null
    }

    fun obtainFlexTreeRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val target = MonitorPackages.primaryTarget(service) ?: return null
        val self = service.packageName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pickTargetWindow(service.windows, target, preferActive = true)?.let { return it }
            pickTargetWindow(service.windows, target, preferFocused = true)?.let { return it }
            pickTargetWindow(service.windows, target, preferAny = true)?.let { return it }
        }

        val active = service.rootInActiveWindow
        if (active != null) {
            val pkg = active.packageName?.toString()
            when (pkg) {
                target -> return active
                self -> {
                    try {
                        active.recycle()
                    } catch (_: Exception) {
                    }
                }
                else -> {
                    try {
                        active.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return null
    }

    /** Usuario en la actividad de PichiX (no solo overlay flotante sobre Flex). */
    private fun isFullScreenPichix(service: AccessibilityService): Boolean {
        val self = service.packageName
        val active = service.rootInActiveWindow ?: return false
        return try {
            if (active.packageName?.toString() != self) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val flexTarget = MonitorPackages.primaryTarget(service)
                val hasFlexLayer = service.windows.orEmpty().any { window ->
                    val root = window.root ?: return@any false
                    try {
                        root.packageName?.toString() == flexTarget
                    } finally {
                        try {
                            root.recycle()
                        } catch (_: Exception) {
                        }
                    }
                }
                if (hasFlexLayer) return false
            }
            true
        } finally {
            try {
                active.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun pickTargetWindow(
        windows: List<AccessibilityWindowInfo>?,
        target: String,
        preferActive: Boolean = false,
        preferFocused: Boolean = false,
        preferAny: Boolean = false,
    ): AccessibilityNodeInfo? {
        if (windows.isNullOrEmpty()) return null
        for (window in windows) {
            when {
                preferActive && !window.isActive -> continue
                preferFocused && !window.isFocused -> continue
                !preferAny && !preferActive && !preferFocused -> continue
            }
            val root = window.root ?: continue
            try {
                if (root.packageName?.toString() == target) {
                    Log.d(TAG, "Raíz Flex: active=${window.isActive} focused=${window.isFocused}")
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
