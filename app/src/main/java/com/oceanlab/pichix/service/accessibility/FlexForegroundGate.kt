package com.oceanlab.pichix.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.oceanlab.pichix.data.AppSettings

/**
 * Motor solo con Flex al frente: ventana activa, ventana accesible de Flex o UI Flex visible.
 * Sin periodo de gracia por eventos antiguos (evita detección con Flex minimizado).
 */
object FlexForegroundGate {

    private const val TAG = "PichiXForeground"

    fun onAccessibilityEvent(event: AccessibilityEvent, monitoredTargets: Set<String>) {
        // Reservado para telemetría futura; la comprobación real es en allowMotor().
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg !in monitoredTargets) return
    }

    fun allowMotor(
        service: AccessibilityService,
        settings: AppSettings,
        targetPkg: String,
        flexUiVisible: () -> Boolean = { false },
    ): Boolean {
        if (!settings.flexOnlyWhenForeground) return true

        val allowed = activeWindowPackage(service) == targetPkg ||
            anyWindowHasPackage(service, targetPkg, service.packageName) ||
            flexUiVisible()

        if (!allowed && settings.debugLogEnabled) {
            Log.d(
                TAG,
                "Motor pausado: Flex no en primer plano (target=$targetPkg active=${activeWindowPackage(service)})",
            )
        }
        return allowed
    }

    private fun activeWindowPackage(service: AccessibilityService): String? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** Cualquier ventana accesible del paquete Flex (aunque Android marque otra como «activa»). */
    private fun anyWindowHasPackage(
        service: AccessibilityService,
        targetPkg: String,
        selfPkg: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        for (win in service.windows ?: emptyList()) {
            when (win.type) {
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                AccessibilityWindowInfo.TYPE_SYSTEM -> continue
            }
            val root = win.root ?: continue
            try {
                when (root.packageName?.toString()) {
                    targetPkg -> return true
                    selfPkg, null -> Unit
                    else -> Unit
                }
            } finally {
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }
}
