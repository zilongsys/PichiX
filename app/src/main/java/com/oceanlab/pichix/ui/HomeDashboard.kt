package com.oceanlab.pichix.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.OfferLogEntry
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus
import com.oceanlab.pichix.util.PermissionStatusHelper
import java.util.Locale

/**
 * Tarjetas dinámicas de Inicio: lista previa («qué falta para que el bot funcione», con botón
 * para arreglarlo) y resumen de hoy con la última oferta. Las estadísticas se leen en segundo plano
 * y las actualizaciones se agrupan para no recalcular en cada broadcast.
 */
class HomeDashboard(private val fragment: Fragment) {

    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private val refreshRunnable = Runnable { doRefresh() }

    private class Check(
        val required: Boolean,
        val ok: Boolean,
        val labelRes: Int,
        val fixRes: Int,
        val fix: () -> Unit,
    )

    fun bind(view: View) {
        root = view
    }

    fun release() {
        main.removeCallbacks(refreshRunnable)
        root = null
    }

    /** Pide un refresco; varias llamadas seguidas se agrupan en una. */
    fun refresh() {
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 150)
    }

    private fun alive() = root != null && fragment.isAdded && fragment.view != null

    private fun doRefresh() {
        if (!alive()) return
        renderPreflight()
        loadToday()
    }

    // ---------- Lista previa ----------

    private fun checks(): List<Check> {
        val ctx = fragment.requireContext()
        val battery = (ctx.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
        return listOf(
            Check(true, PermissionStatusHelper.isAccessibilityServiceEnabled(ctx),
                R.string.home_pf_accessibility, R.string.home_pf_fix_activate) {
                openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            },
            Check(true, MonitorPackages.isConfigured(ctx),
                R.string.home_pf_package, R.string.home_pf_fix_configure) {
                (fragment.activity as? MainActivity)?.navigateToTab(1)
            },
            Check(false, PermissionStatusHelper.isNotificationListenerEnabled(ctx),
                R.string.home_pf_notifications, R.string.home_pf_fix_activate) {
                openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            },
            Check(false, battery,
                R.string.home_pf_battery, R.string.home_pf_fix_adjust) {
                openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            },
        )
    }

    private fun openSettings(action: String) {
        try {
            fragment.startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            fragment.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun renderPreflight() {
        val view = root ?: return
        val card = view.findViewById<View>(R.id.homePreflightCard) ?: return
        val list = view.findViewById<LinearLayout>(R.id.homePreflightList) ?: return
        val title = view.findViewById<TextView>(R.id.homePreflightTitle) ?: return
        val missing = checks().filter { !it.ok }
        list.removeAllViews()
        if (missing.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        val anyRequired = missing.any { it.required }
        title.setText(if (anyRequired) R.string.home_pf_title_required else R.string.home_pf_title_recommended)
        title.setTextColor(
            ContextCompat.getColor(
                fragment.requireContext(),
                if (anyRequired) R.color.amber_400 else R.color.text_hint,
            ),
        )
        val inflater = LayoutInflater.from(fragment.requireContext())
        missing.sortedByDescending { it.required }.forEach { c ->
            val row = inflater.inflate(R.layout.item_home_preflight_row, list, false)
            row.findViewById<TextView>(R.id.pfLabel).setText(c.labelRes)
            row.findViewById<MaterialButton>(R.id.pfFix).apply {
                setText(c.fixRes)
                setOnClickListener { c.fix() }
            }
            list.addView(row)
        }
        card.visibility = View.VISIBLE
    }

    // ---------- Resumen de hoy ----------

    private class TodayData(
        val seen: Int,
        val accepted: Int,
        val missed: Int,
        val earned: Double,
        val last: OfferLogEntry?,
    )

    private fun loadToday() {
        val appCtx = fragment.requireContext().applicationContext
        Thread {
            val data = try {
                val logger = OfferLogger(appCtx)
                val stats = logger.getTodayStats()
                TodayData(stats.seen, stats.accepted, stats.miss, stats.totalEarned, logger.getRecentEntries(1).firstOrNull())
            } catch (e: Exception) {
                null
            }
            main.post { if (alive() && data != null) renderToday(data) }
        }.start()
    }

    private fun renderToday(d: TodayData) {
        val view = root ?: return
        view.findViewById<TextView>(R.id.homeStatSeen)?.text = d.seen.toString()
        view.findViewById<TextView>(R.id.homeStatAccepted)?.text = d.accepted.toString()
        view.findViewById<TextView>(R.id.homeStatMissed)?.text = d.missed.toString()
        view.findViewById<TextView>(R.id.homeStatEarned)?.text =
            String.format(Locale.US, "\$%.0f", d.earned)
        view.findViewById<TextView>(R.id.homeLastOffer)?.text = lastOfferText(d.last)
    }

    private fun lastOfferText(e: OfferLogEntry?): String {
        val f = fragment
        if (e == null) return f.getString(R.string.home_last_offer_none)
        val status = f.getString(
            when (e.status) {
                OfferStatus.ACCEPTED -> R.string.home_last_status_accepted
                OfferStatus.REJECTED -> R.string.home_last_status_rejected
                OfferStatus.MISS -> R.string.home_last_status_missed
                OfferStatus.SEEN -> R.string.home_last_status_seen
                else -> R.string.home_last_status_other
            },
        )
        val price = String.format(Locale.US, "\$%.2f", e.price)
        val station = e.station.ifBlank { "—" }
        return f.getString(R.string.home_last_offer, price, station, status, agoText(System.currentTimeMillis() - e.timestamp))
    }

    private fun agoText(deltaMs: Long): String {
        val min = (deltaMs / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> fragment.getString(R.string.home_ago_now)
            min < 60 -> fragment.getString(R.string.home_ago_minutes, min.toInt())
            else -> fragment.getString(R.string.home_ago_hours, (min / 60).toInt())
        }
    }
}
