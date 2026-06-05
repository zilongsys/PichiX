package com.oceanlab.pichix.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.ui.MainActivity

/**
 * Panel flotante PichiX: ON/OFF del bot, pausa de navegación (motor) y prueba Return 2.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private lateinit var settings: AppSettings

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateOverlayAppearance()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver,
            IntentFilter().apply {
                addAction(MainActivity.BOT_STATE_CHANGED)
                addAction(MainActivity.BOT_PAUSED)
                addAction(MainActivity.BOT_RESUMED)
                addAction(PichixAccessibilityService.MOTOR_PAUSE_CHANGED)
            },
        )
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        settings = AppSettings(this)
        if (intent?.action == ACTION_REBUILD) {
            removeOverlay()
            showOverlay()
        } else {
            applyFabVisibility()
            updateOverlayAppearance()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_pichix_panel, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = if (settings.overlayPosX >= 0) settings.overlayPosX else (dm.widthPixels * 0.72f).toInt()
            y = if (settings.overlayPosY >= 0) settings.overlayPosY else (dm.heightPixels * 0.35f).toInt()
        }

        val btnOnOff = view.findViewById<FrameLayout>(R.id.overlayBtnOnOff)
        val btnPause = view.findViewById<FrameLayout>(R.id.overlayBtnPause)
        val btnTest = view.findViewById<FrameLayout>(R.id.overlayBtnTest)

        attachDragOrTap(btnOnOff, view, params) { if (!it) toggleBot() }
        attachDragOrTap(btnPause, view, params) {
            if (!it) PichixAccessibilityService.toggleMotorPauseForNavigation(this)
        }
        attachDragOrTap(btnTest, view, params) {
            if (!it) PichixAccessibilityService.returnToOffers()
        }

        windowManager?.addView(view, params)
        overlayView = view
        applyFabVisibility()
        updateOverlayAppearance()
    }

    private fun attachDragOrTap(
        handle: View,
        panel: View,
        params: WindowManager.LayoutParams,
        onTap: (moved: Boolean) -> Unit,
    ) {
        var dragX = 0
        var dragY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = params.x
                    dragY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = dragX + dx
                    params.y = dragY + dy
                    windowManager?.updateViewLayout(panel, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    settings.overlayPosX = params.x
                    settings.overlayPosY = params.y
                    onTap(moved)
                    true
                }
                else -> false
            }
        }
    }

    private fun applyFabVisibility() {
        val view = overlayView ?: return
        view.findViewById<FrameLayout>(R.id.overlayBtnOnOff)?.visibility =
            if (settings.overlayEnabled) View.VISIBLE else View.GONE
        view.findViewById<FrameLayout>(R.id.overlayBtnPause)?.visibility =
            if (settings.overlayMotorPauseFabEnabled) View.VISIBLE else View.GONE
        view.findViewById<FrameLayout>(R.id.overlayBtnTestReturn)?.visibility =
            if (settings.overlayTestReturnEnabled) View.VISIBLE else View.GONE
    }

    private fun toggleBot() {
        val enabling = !settings.isBotEnabled || PichixAccessibilityService.pausedAfterAccept
        if (enabling) {
            PichixAccessibilityService.pausedAfterAccept = false
            PichixAccessibilityService.motorPausedForNavigation = false
            settings.isBotEnabled = true
            BotServiceCoordinator.syncForegroundService(this)
            PichixAccessibilityService.syncEngine(this)
        } else {
            settings.isBotEnabled = false
            PichixAccessibilityService.notifyBotDisabled()
            PichixForegroundService.stop(this)
        }
        updateOverlayAppearance()
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(MainActivity.BOT_STATE_CHANGED))
        PichixForegroundService.refreshNotification(this)
    }

    private fun updateOverlayAppearance() {
        val view = overlayView ?: return
        settings = AppSettings(this)
        applyFabVisibility()

        view.findViewById<TextView>(R.id.overlayLabelOnOff)?.let { label ->
            val on = settings.isBotEnabled && !PichixAccessibilityService.pausedAfterAccept
            label.text = if (on) "ON" else "OFF"
            val color = if (on) R.color.green_400 else R.color.coral_600
            label.setTextColor(ContextCompat.getColor(this, color))
            view.findViewById<FrameLayout>(R.id.overlayBtnOnOff)?.alpha = if (on) 1f else 0.85f
        }

        view.findViewById<TextView>(R.id.overlayLabelPause)?.let { label ->
            val paused = PichixAccessibilityService.motorPausedForNavigation
            label.text = if (paused) "▶" else "⏸"
            view.findViewById<FrameLayout>(R.id.overlayBtnPause)?.alpha = if (paused) 0.85f else 1f
        }
    }

    private fun removeOverlay() {
        overlayView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    companion object {
        private const val ACTION_REBUILD = "com.oceanlab.pichix.OVERLAY_REBUILD"

        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun sync(context: Context) {
            val settings = AppSettings(context)
            if (!settings.hasAnyOverlayFab()) {
                stop(context)
            } else {
                context.startService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_REBUILD),
                )
            }
        }
    }
}
