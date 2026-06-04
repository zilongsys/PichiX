package com.oceanlab.pichix.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.oceanlab.pichix.data.AppSettings

/**
 * Decide si el motor (grabber, scroll, clics, return) puede actuar con Flex al frente.
 *
 * Capas (Flex a veces no actualiza TYPE_WINDOW_STATE_CHANGED):
 * 1. Ventana activa = paquete Flex
 * 2. Ventanas del sistema: activas, con foco o visibles con paquete Flex
 * 3. Marcadores de UI Flex en el árbol de accesibilidad (ids + texto)
 * 4. Eventos recientes del paquete Flex (state, content, focus, windows)
 */
object FlexForegroundGate {

    private const val TAG = "PichiXForeground"
    private const val TARGET_EVENT_GRACE_MS = 5000L
    private const val TARGET_CONTENT_GRACE_MS = 8000L
    private const val OTHER_APP_BLOCK_MS = 400L

    @Volatile
    private var lastTargetWindowEventMs = 0L

    @Volatile
    private var lastTargetContentEventMs = 0L

    @Volatile
    private var lastOtherAppWindowEventMs = 0L

    @Volatile
    private var lastOtherAppPackage: String? = null

    fun onAccessibilityEvent(event: AccessibilityEvent, monitoredTargets: Set<String>) {
        val pkg = event.packageName?.toString().orEmpty()
        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg.isEmpty()) return
                if (pkg in monitoredTargets) {
                    lastTargetWindowEventMs = now
                    lastTargetContentEventMs = now
                } else {
                    lastOtherAppWindowEventMs = now
                    lastOtherAppPackage = pkg
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg in monitoredTargets) {
                    lastTargetContentEventMs = now
                    lastTargetWindowEventMs = now
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (pkg in monitoredTargets) {
                    lastTargetWindowEventMs = now
                }
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

        if (flexUiVisible()) {
            val now = System.currentTimeMillis()
            lastTargetWindowEventMs = now
            lastTargetContentEventMs = now
            return true
        }

        val verdict = probe(service, targetPkg, service.packageName, flexUiVisible)
        val allowed = when (verdict) {
            Verdict.TARGET_FOREGROUND -> true
            Verdict.OTHER_FOREGROUND -> false
            Verdict.UNCERTAIN -> uncertainAllowsTarget()
        }
        if (!allowed && settings.debugLogEnabled) {
            Log.d(
                TAG,
                "Motor pausado (sin Flex primer plano): verdict=$verdict other=$lastOtherAppPackage",
            )
        }
        return allowed
    }

    private fun uncertainAllowsTarget(): Boolean {
        val now = System.currentTimeMillis()
        val recentTarget = now - lastTargetWindowEventMs <= TARGET_EVENT_GRACE_MS ||
            now - lastTargetContentEventMs <= TARGET_CONTENT_GRACE_MS
        val otherNotRecent = now - lastOtherAppWindowEventMs > OTHER_APP_BLOCK_MS
        return recentTarget && otherNotRecent
    }

    private enum class Verdict { TARGET_FOREGROUND, OTHER_FOREGROUND, UNCERTAIN }

    private fun probe(
        service: AccessibilityService,
        targetPkg: String,
        selfPkg: String,
        flexUiVisible: () -> Boolean,
    ): Verdict {
        if (flexUiVisible()) return Verdict.TARGET_FOREGROUND

        val active = service.rootInActiveWindow
        val activePkg = try {
            active?.packageName?.toString()
        } finally {
            try {
                active?.recycle()
            } catch (_: Exception) {
            }
        }

        when {
            activePkg == targetPkg -> return Verdict.TARGET_FOREGROUND
            activePkg != null && activePkg != selfPkg && activePkg != targetPkg ->
                return Verdict.OTHER_FOREGROUND
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            var targetVisible = false
            var otherForeground = false
            for (win in service.windows ?: emptyList()) {
                when (win.type) {
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    AccessibilityWindowInfo.TYPE_SYSTEM -> continue
                }
                val root = win.root ?: continue
                val winPkg = try {
                    root.packageName?.toString()
                } finally {
                    try {
                        root.recycle()
                    } catch (_: Exception) {
                    }
                }
                when (winPkg) {
                    targetPkg -> {
                        if (win.isActive || win.isFocused) {
                            return Verdict.TARGET_FOREGROUND
                        }
                        targetVisible = true
                    }
                    selfPkg, null -> Unit
                    else -> {
                        if (win.isActive) otherForeground = true
                    }
                }
            }
            if (targetVisible) return Verdict.TARGET_FOREGROUND
            if (otherForeground) return Verdict.OTHER_FOREGROUND
        }

        if (activePkg == selfPkg) return Verdict.UNCERTAIN

        return Verdict.UNCERTAIN
    }
}
