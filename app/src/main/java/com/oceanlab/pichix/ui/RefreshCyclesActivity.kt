package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.service.RefreshClickGuard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ventana de ciclos Refresh: de un banner «demasiados toques» al siguiente. */
class RefreshCyclesActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvProgress: TextView
    private lateinit var bar: ProgressBar
    private lateinit var header: View
    private lateinit var rv: RecyclerView
    private val adapter = CycleAdapter()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RefreshClickGuard.ACTION_CHANGED) bind()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyFromSettings(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.refresh_cycles_title)
        setContentView(R.layout.activity_refresh_cycles)

        tvSummary = findViewById(R.id.tvRefreshCyclesSummary)
        tvProgress = findViewById(R.id.tvRefreshCyclesProgress)
        bar = findViewById(R.id.barRefreshCyclesProgress)
        header = findViewById(R.id.rowRefreshCyclesHeader)
        rv = findViewById(R.id.rvRefreshCycles)
        rv.adapter = adapter

        findViewById<MaterialButton>(R.id.btnRefreshCyclesReload).setOnClickListener { bind() }
        findViewById<MaterialButton>(R.id.btnRefreshCyclesClear).setOnClickListener { confirmClear() }
        bind()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            changedReceiver,
            IntentFilter(RefreshClickGuard.ACTION_CHANGED),
        )
    }

    override fun onStop() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(changedReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun confirmClear() {
        AlertDialog.Builder(this, R.style.SparkAlertDialogTheme)
            .setTitle(R.string.config_refresh_guard_clear)
            .setMessage(R.string.refresh_cycles_clear_confirm)
            .setPositiveButton(R.string.config_refresh_guard_clear) { _, _ ->
                RefreshClickGuard.clearMemory(this)
                bind()
                Toast.makeText(this, R.string.config_refresh_guard_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bind() {
        val st = RefreshClickGuard.status(this)
        tvSummary.text = buildString {
            append(RefreshClickGuard.summaryText(this@RefreshCyclesActivity))
            append("\n\n")
            append(getString(R.string.refresh_cycles_intro))
        }
        if (st.learnedLimit != null) {
            tvProgress.text = getString(
                R.string.refresh_cycles_progress,
                st.windowClicks,
                st.learnedLimit,
                st.progressPercent,
                st.warnAt ?: 0,
                st.pauseAt ?: 0,
            )
            bar.visibility = View.VISIBLE
            bar.progress = st.progressPercent
        } else {
            tvProgress.text = getString(R.string.refresh_cycles_progress_none)
            bar.visibility = View.GONE
        }
        val rows = mutableListOf<CycleRow>()
        val closed = st.episodes.asReversed()
        st.live?.let { rows.add(CycleRow(it, closed.size + 1, live = true)) }
        closed.forEachIndexed { i, ep ->
            rows.add(CycleRow(ep, closed.size - i, live = false))
        }
        header.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(this, rows)
        if (rows.isEmpty()) {
            tvProgress.append("\n${getString(R.string.refresh_cycles_empty)}")
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "—"
        return timeFmt.format(Date(ms))
    }

    private fun paceText(e: RefreshClickGuard.Episode): String {
        return if (e.clicksPerMin > 0.0) {
            "≈%.1f".format(Locale.US, e.clicksPerMin)
        } else {
            "—"
        }
    }

    private fun prevText(e: RefreshClickGuard.Episode): String = when {
        e.pausedPreventively -> getString(R.string.refresh_cycles_prev_pause)
        e.warned -> getString(R.string.refresh_cycles_prev_warn)
        else -> getString(R.string.refresh_cycles_prev_none)
    }

    private fun extraSpan(e: RefreshClickGuard.Episode, live: Boolean): String {
        val start = if (e.firstClickAtMs > 0L) formatTime(e.firstClickAtMs) else "—"
        val end = when {
            e.lastClickAtMs > 0L -> formatTime(e.lastClickAtMs)
            live -> getString(R.string.refresh_cycles_until_now)
            else -> "—"
        }
        val burst = getString(R.string.refresh_cycles_burst_n, e.burstClicks)
        val normal = getString(R.string.refresh_cycles_normal_n, e.normalClicks)
        return "$start → $end\n$burst · $normal"
    }

    private fun extraWait(e: RefreshClickGuard.Episode, live: Boolean): String {
        val wait = when {
            e.waitAfterPreviousMs > 0L -> getString(
                R.string.refresh_cycles_extra_wait,
                RefreshClickGuard.formatDuration(e.waitAfterPreviousMs),
            )
            !live && e.previousBannerAtMs <= 0L -> getString(R.string.refresh_cycles_first_short)
            else -> null
        }
        val until = if (!live) {
            getString(
                R.string.refresh_cycles_extra_banner,
                RefreshClickGuard.formatDuration(e.elapsedMs),
            )
        } else {
            null
        }
        return listOfNotNull(wait, until).joinToString("\n").ifEmpty { "—" }
    }

    private fun extraGaps(e: RefreshClickGuard.Episode): String {
        if (e.clicks < 2) {
            return if (e.idleGaps > 0) getString(R.string.refresh_cycles_extra_idle, e.idleGaps) else "—"
        }
        val gaps = getString(
            R.string.refresh_cycles_extra_int,
            RefreshClickGuard.formatDuration(e.minGapMs),
            RefreshClickGuard.formatDuration(e.avgGapMs),
            RefreshClickGuard.formatDuration(e.maxGapMs),
        )
        return if (e.idleGaps > 0) {
            "$gaps · ${getString(R.string.refresh_cycles_extra_idle, e.idleGaps)}"
        } else {
            gaps
        }
    }

    private fun extraLast(e: RefreshClickGuard.Episode): String =
        getString(R.string.refresh_cycles_extra_last, e.clicksLast60s, e.clicksLast5min)

    private data class CycleRow(
        val episode: RefreshClickGuard.Episode,
        val index: Int,
        val live: Boolean,
    )

    private class CycleAdapter : RecyclerView.Adapter<CycleAdapter.Holder>() {
        private var rows: List<CycleRow> = emptyList()
        private var activity: RefreshCyclesActivity? = null

        fun submit(host: RefreshCyclesActivity, next: List<CycleRow>) {
            activity = host
            rows = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_refresh_cycle, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val host = activity ?: return
            holder.bind(host, rows[position])
        }

        override fun getItemCount(): Int = rows.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val colIndex = view.findViewById<TextView>(R.id.tvColIndex)
            private val colClicks = view.findViewById<TextView>(R.id.tvColClicks)
            private val colPace = view.findViewById<TextView>(R.id.tvColPace)
            private val colActive = view.findViewById<TextView>(R.id.tvColActive)
            private val colBanner = view.findViewById<TextView>(R.id.tvColBanner)
            private val colPrev = view.findViewById<TextView>(R.id.tvColPrev)
            private val extraSpan = view.findViewById<TextView>(R.id.tvExtraSpan)
            private val extraWait = view.findViewById<TextView>(R.id.tvExtraWait)
            private val extraGaps = view.findViewById<TextView>(R.id.tvExtraGaps)
            private val extraLast = view.findViewById<TextView>(R.id.tvExtraLast)

            fun bind(host: RefreshCyclesActivity, row: CycleRow) {
                val e = row.episode
                val ctx = host
                colIndex.text = if (row.live) "${row.index}*" else row.index.toString()
                colClicks.text = e.clicks.toString()
                colPace.text = host.paceText(e)
                val activeMs = if (e.activeClickingMs > 0L) e.activeClickingMs else e.clickingMs
                colActive.text = if (activeMs > 0L) RefreshClickGuard.formatDuration(activeMs) else "—"
                colBanner.text = if (row.live) {
                    ctx.getString(R.string.refresh_cycles_live_short)
                } else {
                    host.formatTime(e.atMs)
                }
                colPrev.text = host.prevText(e)
                extraSpan.text = host.extraSpan(e, row.live)
                extraWait.text = host.extraWait(e, row.live)
                extraGaps.text = host.extraGaps(e)
                extraLast.text = host.extraLast(e)

                val titleColor = ContextCompat.getColor(
                    ctx,
                    if (row.live) R.color.badge_amber_text else R.color.text_primary,
                )
                val extraColor = ContextCompat.getColor(
                    ctx,
                    if (row.live) R.color.badge_amber_text else R.color.text_secondary,
                )
                colIndex.setTextColor(titleColor)
                colClicks.setTextColor(titleColor)
                colPace.setTextColor(titleColor)
                colActive.setTextColor(titleColor)
                colBanner.setTextColor(titleColor)
                colPrev.setTextColor(titleColor)
                extraSpan.setTextColor(extraColor)
                extraWait.setTextColor(extraColor)
                extraGaps.setTextColor(extraColor)
                extraLast.setTextColor(extraColor)
                itemView.setBackgroundColor(
                    ContextCompat.getColor(ctx, if (row.live) R.color.amber_bg else R.color.bg_card),
                )
            }
        }
    }
}
