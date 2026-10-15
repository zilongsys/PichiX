package com.oceanlab.pichix.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.service.RefreshClickGuard

/** Ventana de ciclos Refresh: de un banner «demasiados toques» al siguiente. */
class RefreshCyclesActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyFromSettings(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.refresh_cycles_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_page))
        }

        tvSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_primary))
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        root.addView(tvSummary)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        actions.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.config_refresh_guard_update)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { bind() }
            },
        )
        actions.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.config_refresh_guard_clear)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
                setOnClickListener { confirmClear() }
            },
        )
        root.addView(actions)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(list)
        }
        root.addView(scroll)
        setContentView(root)
        bind()
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
        list.removeAllViews()
        val closed = st.episodes.asReversed()
        if (st.live == null && closed.isEmpty()) {
            list.addView(hint(getString(R.string.refresh_cycles_empty)))
            return
        }
        st.live?.let { live ->
            list.addView(cycleCard(live, index = closed.size + 1, live = true))
        }
        closed.forEachIndexed { i, ep ->
            list.addView(cycleCard(ep, index = closed.size - i, live = false))
        }
    }

    private fun cycleCard(e: RefreshClickGuard.Episode, index: Int, live: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(color(if (live) R.color.amber_bg else R.color.bg_card))
                setStroke(dp(1), color(if (live) R.color.amber_400 else R.color.border_subtle))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val title = if (live) {
            getString(R.string.refresh_cycles_live_title, index)
        } else {
            getString(R.string.refresh_cycles_closed_title, index)
        }
        card.addView(label(title, 15f, true, if (live) R.color.badge_amber_text else R.color.text_primary))

        val from = when {
            e.previousBannerAtMs > 0L -> RefreshClickGuard.formatClock(e.previousBannerAtMs)
            e.firstClickAtMs > 0L -> RefreshClickGuard.formatClock(e.firstClickAtMs)
            else -> "—"
        }
        val to = if (live) {
            getString(R.string.refresh_cycles_until_now)
        } else {
            RefreshClickGuard.formatClock(e.atMs)
        }
        card.addView(row(getString(R.string.refresh_cycles_span), "$from  →  $to"))
        if (e.previousBannerAtMs > 0L) {
            card.addView(
                row(
                    getString(R.string.refresh_cycles_from_prev_banner),
                    RefreshClickGuard.formatDuration(e.cycleMs.coerceAtLeast(0L)),
                ),
            )
            if (e.waitAfterPreviousMs > 0L) {
                card.addView(
                    row(
                        getString(R.string.refresh_cycles_wait_first),
                        RefreshClickGuard.formatDuration(e.waitAfterPreviousMs),
                    ),
                )
            }
        } else if (!live) {
            card.addView(row(getString(R.string.refresh_cycles_from_prev_banner), getString(R.string.refresh_cycles_first)))
        }

        val clickSpan = if (e.firstClickAtMs > 0L && e.lastClickAtMs >= e.firstClickAtMs) {
            "${RefreshClickGuard.formatClock(e.firstClickAtMs)}  →  ${RefreshClickGuard.formatClock(e.lastClickAtMs)}"
        } else {
            "—"
        }
        card.addView(row(getString(R.string.refresh_cycles_clicking), RefreshClickGuard.formatDuration(e.clickingMs)))
        card.addView(row(getString(R.string.refresh_cycles_click_span), clickSpan))
        if (!live) {
            card.addView(
                row(
                    getString(R.string.refresh_cycles_until_banner),
                    RefreshClickGuard.formatDuration(e.elapsedMs),
                ),
            )
        }

        card.addView(
            row(
                getString(R.string.refresh_cycles_clicks),
                "${e.clicks}  (${getString(R.string.refresh_cycles_burst_n, e.burstClicks)} · " +
                    "${getString(R.string.refresh_cycles_normal_n, e.normalClicks)})",
            ),
        )
        card.addView(
            row(
                getString(R.string.refresh_cycles_pace),
                "≈${"%.1f".format(java.util.Locale.US, e.clicksPerMin)}/min  ·  " +
                    getString(R.string.refresh_cycles_last_min, e.clicksLast60s) + "  ·  " +
                    getString(R.string.refresh_cycles_last_5min, e.clicksLast5min),
            ),
        )
        if (e.clicks >= 2) {
            card.addView(
                row(
                    getString(R.string.refresh_cycles_gaps),
                    getString(
                        R.string.refresh_cycles_gaps_values,
                        RefreshClickGuard.formatDuration(e.minGapMs),
                        RefreshClickGuard.formatDuration(e.avgGapMs),
                        RefreshClickGuard.formatDuration(e.maxGapMs),
                    ),
                ),
            )
        }
        if (e.idleGaps > 0) {
            card.addView(row(getString(R.string.refresh_cycles_idle), e.idleGaps.toString()))
        }
        card.addView(
            row(
                getString(R.string.refresh_cycles_preventive),
                buildString {
                    append(if (e.warned) getString(R.string.refresh_cycles_warned_yes) else getString(R.string.refresh_cycles_warned_no))
                    append("  ·  ")
                    append(if (e.pausedPreventively) getString(R.string.refresh_cycles_paused_yes) else getString(R.string.refresh_cycles_paused_no))
                },
            ),
        )
        if (!live) {
            card.addView(row(getString(R.string.refresh_cycles_banner_at), RefreshClickGuard.formatClock(e.atMs)))
        }
        return card
    }

    private fun row(k: String, v: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(2))
            addView(label(k, 11f, false, R.color.text_hint))
            addView(label(v, 13f, false, R.color.text_primary))
        }

    private fun label(text: String, size: Float, bold: Boolean, colorRes: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color(colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun hint(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), dp(24))
        }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
