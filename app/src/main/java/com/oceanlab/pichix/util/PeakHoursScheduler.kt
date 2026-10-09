package com.oceanlab.pichix.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.BotEventLog
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatsAnalyzer
import com.oceanlab.pichix.ui.MainActivity
import java.util.Calendar
import java.util.Locale

/**
 * Avisa (con el bot APAGADO) en las horas donde históricamente más se detectan ofertas.
 * Independiente del FGS / motor: AlarmManager + notificación propia.
 */
object PeakHoursScheduler {

    private const val TAG = "PeakHours"
    const val CHANNEL_ID = "pichix_peak_hours"
    private const val NOTIF_ID_BASE = 8100
    private const val ACTION_PEAK = "com.oceanlab.pichix.PEAK_HOUR_REMIND"
    private const val EXTRA_HOUR = "hour"
    private const val LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_PEAK_HOURS = 4
    private const val MIN_OFFERS_PER_HOUR = 5
    private const val REQUEST_BASE = 9200

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.peak_hours_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.peak_hours_channel_desc)
            },
        )
    }

    /** Recalcula franjas pico y programa las próximas alarmas. */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        val settings = AppSettings(app)
        cancelAll(app)
        if (!settings.peakHoursNotifyEnabled) return

        val peaks = computePeakHours(app, settings)
        if (peaks.isEmpty()) {
            Log.d(TAG, "sin franjas pico suficientes")
            return
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val leadMin = settings.peakHoursLeadMinutes
        val now = System.currentTimeMillis()
        for (hour in peaks) {
            val triggerAt = nextTriggerMs(hour, leadMin, now)
            val pi = pendingForHour(app, hour)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    @Suppress("DEPRECATION")
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "sin permiso exact alarm: ${e.message}")
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e: Exception) {
                Log.e(TAG, "schedule hour=$hour: ${e.message}")
            }
        }
        Log.d(TAG, "programadas horas=$peaks lead=${leadMin}m")
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (hour in 0..23) {
            try {
                am.cancel(pendingForHour(context, hour))
            } catch (_: Exception) {
            }
        }
    }

    fun computePeakHours(context: Context, settings: AppSettings = AppSettings(context)): List<Int> {
        val since = System.currentTimeMillis() - LOOKBACK_MS
        val entries = OfferLogger(context).getEntriesSince(since)
        if (entries.isEmpty()) return emptyList()
        val report = OfferStatsAnalyzer.analyze(entries)
        val minCount = settings.peakHoursMinOffers.coerceAtLeast(1)
        return report.hourly
            .filter { it.count >= minCount }
            .sortedByDescending { it.score }
            .take(MAX_PEAK_HOURS)
            .map { it.hour }
            .sorted()
    }

    fun onAlarm(context: Context, hour: Int) {
        val app = context.applicationContext
        ensureChannel(app)
        val settings = AppSettings(app)
        if (!settings.peakHoursNotifyEnabled) {
            reschedule(app)
            return
        }
        // Solo avisar si el bot NO está activo (requisito del usuario).
        if (settings.isBotEnabled) {
            BotEventLog.log(app, BotEventLog.CAT_BOT, "Aviso franja $hour:00 omitido — bot ya activo")
            reschedule(app)
            return
        }
        val label = String.format(Locale.US, "%02d:00", hour)
        val title = app.getString(R.string.peak_hours_notif_title)
        val text = app.getString(R.string.peak_hours_notif_text, label)
        val open = PendingIntent.getActivity(
            app,
            NOTIF_ID_BASE + hour,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingFlags(),
        )
        val notif = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_notifications)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(NOTIF_ID_BASE + hour, notif)
            BotEventLog.log(app, BotEventLog.CAT_BOT, "Aviso franja pico $label — enciende el bot")
        } catch (e: SecurityException) {
            Log.w(TAG, "notify denied: ${e.message}")
        }
        reschedule(app)
    }

    private fun nextTriggerMs(hour: Int, leadMinutes: Int, nowMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            add(Calendar.MINUTE, -leadMinutes.coerceIn(0, 59))
        }
        if (cal.timeInMillis <= nowMs + 30_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun pendingForHour(context: Context, hour: Int): PendingIntent {
        val intent = Intent(context, PeakHoursAlarmReceiver::class.java)
            .setAction(ACTION_PEAK)
            .putExtra(EXTRA_HOUR, hour)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + hour,
            intent,
            pendingFlags(),
        )
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    const val ACTION_PEAK_INTERNAL = ACTION_PEAK
    const val EXTRA_HOUR_KEY = EXTRA_HOUR
}

class PeakHoursAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PeakHoursScheduler.ACTION_PEAK_INTERNAL) return
        val hour = intent.getIntExtra(PeakHoursScheduler.EXTRA_HOUR_KEY, -1)
        if (hour !in 0..23) return
        PeakHoursScheduler.onAlarm(context, hour)
    }
}

class PeakHoursBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }
        PeakHoursScheduler.reschedule(context)
    }
}
