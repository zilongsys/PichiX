package com.oceanlab.pichix.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.ui.MainActivity

/**
 * Botón flotante FLIXBIX-ON/OFF: activa o desactiva el bot sin abrir la app.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private lateinit var settings: AppSettings

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateOverlayAppearance()
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_flixbix_button, null)
        val label = view.findViewById<TextView>(R.id.overlayLabel)

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

        var dragX = 0
        var dragY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
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
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    settings.overlayPosX = params.x
                    settings.overlayPosY = params.y
                    if (!moved) toggleBot(label)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(view, params)
        overlayView = view
        updateLabel(label)
    }

    private fun toggleBot(label: TextView) {
        val enabling = !settings.isBotEnabled || PichixAccessibilityService.pausedAfterAccept
        if (enabling) {
            PichixAccessibilityService.pausedAfterAccept = false
            settings.isBotEnabled = true
            BotServiceCoordinator.syncForegroundService(this)
            PichixAccessibilityService.syncEngine(this)
        } else {
            settings.isBotEnabled = false
            PichixAccessibilityService.notifyBotDisabled()
            PichixForegroundService.stop(this)
        }
        updateLabel(label)
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(MainActivity.BOT_STATE_CHANGED))
        PichixForegroundService.refreshNotification(this)
    }

    private fun updateOverlayAppearance() {
        overlayView?.findViewById<TextView>(R.id.overlayLabel)?.let { updateLabel(it) }
    }

    private fun updateLabel(label: TextView) {
        val on = settings.isBotEnabled && !PichixAccessibilityService.pausedAfterAccept
        label.text = if (on) "ON" else "OFF"
        val color = if (on) R.color.green_400 else R.color.coral_600
        overlayView?.background = ContextCompat.getDrawable(this, R.drawable.overlay_fab_bg)
        label.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        overlayView?.alpha = if (on) 1f else 0.85f
        label.setTextColor(ContextCompat.getColor(this, color))
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
        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun sync(context: Context) {
            val settings = AppSettings(context)
            if (settings.overlayEnabled) start(context) else stop(context)
        }
    }
}
