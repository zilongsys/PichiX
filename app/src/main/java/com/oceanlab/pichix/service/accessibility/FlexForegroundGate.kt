package com.oceanlab.pichix.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.oceanlab.pichix.data.AppSettings

/**
 * Motor solo con Flex al frente: basta **una** señal positiva (OR).
 * No se bloquea solo porque la ventana «activa» de Android sea otra app o el overlay de PichiX.
 */
object FlexForegroundGate {

    private const val TAG = "PichiXForeground"
    private const val TARGET_EVENT_GRACE_MS = 15_000L

    @Volatile
    private var lastTargetEventMs = 0L

    fun onAccessibilityEvent(event: AccessibilityEvent, monitoredTargets: Set<String>) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg !in monitoredTargets) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                lastTargetEventMs = System.currentTimeMillis()
            }
        }
    }

    fun allowMotor(
        service: AccessibilityService,
        settings: AppSettings,
        targetPkg: String,
        flexUiVisible: () -> Boolean = { false },
    ): Boolean {
        if (!settings.flexOnlyWhenForeground) return true

        val allowed = recentFlexPackageEvent() ||
            activeWindowPackage(service) == targetPkg ||
            anyWindowHasPackage(service, targetPkg, service.packageName) ||
            flexUiVisible()

        if (allowed) {
            lastTargetEventMs = System.currentTimeMillis()
        } else if (settings.debugLogEnabled) {
            Log.d(
                TAG,
                "Motor pausado: ninguna señal Flex (target=$targetPkg active=${activeWindowPackage(service)})",
            )
        }
        return allowed
    }

    private fun recentFlexPackageEvent(): Boolean {
        if (lastTargetEventMs == 0L) return false
        return System.currentTimeMillis() - lastTargetEventMs <= TARGET_EVENT_GRACE_MS
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
