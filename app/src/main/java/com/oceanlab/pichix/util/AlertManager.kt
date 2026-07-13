package com.oceanlab.pichix.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.oceanlab.pichix.data.AppSettings

class AlertManager(private val context: Context) {

    private val settings get() = AppSettings(context)
    private val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "PichiXAlertManager"
        @Volatile private var activePlayer: MediaPlayer? = null
        @Volatile private var stopRunnable: Runnable? = null
        private val handler = Handler(Looper.getMainLooper())

        fun stopGlobal() {
            try {
                stopRunnable?.let { handler.removeCallbacks(it) }
                stopRunnable = null
                activePlayer?.apply {
                    if (isPlaying) stop()
                    reset()
                    release()
                }
            } catch (_: Exception) {
            }
            activePlayer = null
        }
    }

    fun playFlexNotificationAlert(soundUri: String, repeatCount: Int = 2) {
        try {
            stopGlobal()
            prepareSystemVolumeForAlert()
            val uri = resolveUri(soundUri)
            playSound(uri, volumeFraction(), repeatCount.coerceIn(1, 20))
            if (settings.vibrateOnAlert) vibrate()
        } catch (e: Exception) {
            Log.e(TAG, "playFlexNotificationAlert: ${e.message}")
        }
    }

    /** Un solo disparo (pausa / reanudación del bot); no interrumpe alertas en curso si ya suenan. */
    fun playSoundOnce(soundUri: String) {
        try {
            prepareSystemVolumeForAlert()
            val uri = resolveUri(soundUri)
            val player = buildPlayer(uri) ?: return
            val v = volumeFraction()
            player.setVolume(v, v)
            player.setOnCompletionListener { mp ->
                try {
                    mp.release()
                } catch (_: Exception) {
                }
            }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "playSoundOnce: ${e.message}")
        }
    }

    private fun volumeFraction(): Float = (settings.alertVolume / 100f).coerceIn(0f, 1f)

    private fun prepareSystemVolumeForAlert() {
        if (!settings.alertForceVolumeEnabled) return
        val fraction = volumeFraction()
        if (fraction <= 0f) return
        val streams = intArrayOf(
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
        )
        for (stream in streams) {
            val maxVol = audioMgr.getStreamMaxVolume(stream)
            if (maxVol <= 0) continue
            val target = (fraction * maxVol).toInt().coerceIn(1, maxVol)
            try {
                audioMgr.setStreamVolume(stream, target, 0)
            } catch (e: Exception) {
                Log.w(TAG, "setStreamVolume stream=$stream: ${e.message}")
            }
        }
    }

    private fun resolveUri(uriStr: String): Uri =
        if (uriStr.isNotBlank()) {
            try {
                Uri.parse(uriStr)
            } catch (_: Exception) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }

    private fun buildPlayer(uri: Uri): MediaPlayer? {
        val usage = if (settings.alertForceVolumeEnabled) {
            AudioAttributes.USAGE_ALARM
        } else {
            AudioAttributes.USAGE_NOTIFICATION_EVENT
        }
        return try {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                prepare()
            }
        } catch (e: Exception) {
            Log.w(TAG, "buildPlayer fallback: ${e.message}")
            MediaPlayer.create(context, uri)
        }
    }

    private fun playSound(uri: Uri, volume: Float, repeatCount: Int) {
        val player = buildPlayer(uri) ?: return
        val v = volume.coerceIn(0f, 1f)
        player.setVolume(v, v)
        player.isLooping = false
        activePlayer = player

        var remaining = repeatCount
        player.setOnCompletionListener { mp ->
            if (mp !== activePlayer) return@setOnCompletionListener
            remaining--
            if (remaining > 0) {
                try {
                    mp.seekTo(0)
                    mp.start()
                } catch (_: Exception) {
                    stopGlobal()
                }
            } else {
                stopGlobal()
            }
        }

        val maxMs = (player.duration.takeIf { it > 0 } ?: 5000) * repeatCount + 3000L
        val safeStop = Runnable { stopGlobal() }
        stopRunnable = safeStop
        handler.postDelayed(safeStop, maxMs.toLong())
        player.start()
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 300, 100, 400)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(VibratorManager::class.java)
                mgr?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrate: ${e.message}")
        }
    }
}
