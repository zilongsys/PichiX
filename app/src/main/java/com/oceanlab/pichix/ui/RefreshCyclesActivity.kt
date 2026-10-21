package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
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

/** Ventana de ciclos Refresh: de un banner «demasiados toques» al siguiente. */
class RefreshCyclesActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvProgress: TextView
    private lateinit var bar: ProgressBar
    private lateinit var rv: RecyclerView
    private val adapter = CycleAdapter()
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
        adapter.submit(this, rows)
        if (rows.isEmpty()) {
            tvProgress.append("\n${getString(R.string.refresh_cycles_empty)}")
        }
    }

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
            private val title = view.findViewById<TextView>(R.id.tvCycleTitle)
            private val body = view.findViewById<TextView>(R.id.tvCycleBody)

            fun bind(host: RefreshCyclesActivity, row: CycleRow) {
                val e = row.episode
                val ctx = host
                title.text = if (row.live) {
                    ctx.getString(R.string.refresh_cycles_live_title, row.index)
                } else {
                    ctx.getString(R.string.refresh_cycles_closed_title, row.index)
                }
                title.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (row.live) R.color.badge_amber_text else R.color.text_primary,
                    ),
                )
                (itemView.background as? GradientDrawable)?.apply {
                    cornerRadius = host.dp(12).toFloat()
                    setColor(ContextCompat.getColor(ctx, if (row.live) R.color.amber_bg else R.color.bg_card))
                    setStroke(
                        host.dp(1),
                        ContextCompat.getColor(ctx, if (row.live) R.color.amber_400 else R.color.border_subtle),
                    )
                } ?: run {
                    itemView.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = host.dp(12).toFloat()
                        setColor(ContextCompat.getColor(ctx, if (row.live) R.color.amber_bg else R.color.bg_card))
                        setStroke(
                            host.dp(1),
                            ContextCompat.getColor(ctx, if (row.live) R.color.amber_400 else R.color.border_subtle),
                        )
                    }
                }
                body.text = host.formatBody(e, row.live)
            }
        }
    }

    private fun formatBody(e: RefreshClickGuard.Episode, live: Boolean): String {
        val from = when {
            e.previousBannerAtMs > 0L -> RefreshClickGuard.formatClock(e.previousBannerAtMs)
            e.firstClickAtMs > 0L -> RefreshClickGuard.formatClock(e.firstClickAtMs)
            else -> "—"
        }
        val to = when {
            live && e.lastClickAtMs > 0L -> RefreshClickGuard.formatClock(e.lastClickAtMs)
            live -> getString(R.string.refresh_cycles_until_now)
            else -> RefreshClickGuard.formatClock(e.atMs)
        }
        val clickSpan = if (e.firstClickAtMs > 0L && e.lastClickAtMs >= e.firstClickAtMs) {
            "${RefreshClickGuard.formatClock(e.firstClickAtMs)}  →  ${RefreshClickGuard.formatClock(e.lastClickAtMs)}"
        } else {
            "—"
        }
        return buildString {
            append(getString(R.string.refresh_cycles_span)).append(": ").append(from).append("  →  ").append(to).append('\n')
            if (e.previousBannerAtMs > 0L) {
                append(getString(R.string.refresh_cycles_from_prev_banner)).append(": ")
                    .append(RefreshClickGuard.formatDuration(e.cycleMs)).append('\n')
                if (e.waitAfterPreviousMs > 0L) {
                    append(getString(R.string.refresh_cycles_wait_first)).append(": ")
                        .append(RefreshClickGuard.formatDuration(e.waitAfterPreviousMs)).append('\n')
                }
            } else if (!live) {
                append(getString(R.string.refresh_cycles_from_prev_banner)).append(": ")
                    .append(getString(R.string.refresh_cycles_first)).append('\n')
            }
            val active = if (e.activeClickingMs > 0L) e.activeClickingMs else e.clickingMs
            append(getString(R.string.refresh_cycles_clicking)).append(": ")
                .append(RefreshClickGuard.formatDuration(active)).append('\n')
            append(getString(R.string.refresh_cycles_click_span)).append(": ").append(clickSpan).append('\n')
            if (!live) {
                append(getString(R.string.refresh_cycles_until_banner)).append(": ")
                    .append(RefreshClickGuard.formatDuration(e.elapsedMs)).append('\n')
            }
            append(getString(R.string.refresh_cycles_clicks)).append(": ").append(e.clicks)
                .append("  (").append(getString(R.string.refresh_cycles_burst_n, e.burstClicks))
                .append(" · ").append(getString(R.string.refresh_cycles_normal_n, e.normalClicks))
                .append(")\n")
            append(getString(R.string.refresh_cycles_pace)).append(": ")
            if (e.clicksPerMin > 0.0) {
                append("≈").append("%.1f".format(java.util.Locale.US, e.clicksPerMin)).append("/min")
            } else {
                append("—")
            }
            append("  ·  ")
                .append(getString(R.string.refresh_cycles_last_min, e.clicksLast60s)).append("  ·  ")
                .append(getString(R.string.refresh_cycles_last_5min, e.clicksLast5min)).append('\n')
            if (e.clicks >= 2) {
                append(getString(R.string.refresh_cycles_gaps)).append(": ")
                    .append(
                        getString(
                            R.string.refresh_cycles_gaps_values,
                            RefreshClickGuard.formatDuration(e.minGapMs),
                            RefreshClickGuard.formatDuration(e.avgGapMs),
                            RefreshClickGuard.formatDuration(e.maxGapMs),
                        ),
                    ).append('\n')
            }
            if (e.idleGaps > 0) {
                append(getString(R.string.refresh_cycles_idle)).append(": ").append(e.idleGaps).append('\n')
            }
            append(getString(R.string.refresh_cycles_preventive)).append(": ")
            append(if (e.warned) getString(R.string.refresh_cycles_warned_yes) else getString(R.string.refresh_cycles_warned_no))
            append("  ·  ")
            append(if (e.pausedPreventively) getString(R.string.refresh_cycles_paused_yes) else getString(R.string.refresh_cycles_paused_no))
            if (!live) {
                append('\n').append(getString(R.string.refresh_cycles_banner_at)).append(": ")
                    .append(RefreshClickGuard.formatClock(e.atMs))
            }
        }
    }

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
