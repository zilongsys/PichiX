package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.BotEventBurstCodec
import com.oceanlab.pichix.data.BotEventEntry
import com.oceanlab.pichix.data.BotEventLog

class FlexBotLogFragment : Fragment() {

    private companion object {
        const val PAGE_SIZE = 200
        const val FILTER_ALL = "__ALL__"
    }

    private var adapter: BotLogAdapter? = null
    private var loading = false
    private var followTail = true
    private var visibleLimit = PAGE_SIZE
    private var activeCategory: String = FILTER_ALL
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { reloadLogInternal() }
    private val expandedBurstIds = mutableSetOf<Long>()
    private var latestEntries = emptyList<BotEventEntry>()
    private lateinit var toggleCategory: MaterialButtonToggleGroup
    private lateinit var tvPageInfo: TextView
    private lateinit var btnLoadMore: MaterialButton

    private val categoryFilters = listOf(
        FILTER_ALL to R.string.bot_log_filter_all,
        BotEventLog.CAT_BOT to R.string.bot_log_filter_bot,
        BotEventLog.CAT_BURST to R.string.bot_log_filter_burst,
        BotEventLog.CAT_CLICK to R.string.bot_log_filter_click,
        BotEventLog.CAT_SCROLL to R.string.bot_log_filter_scroll,
        BotEventLog.CAT_OFFER to R.string.bot_log_filter_offer,
        BotEventLog.CAT_SCREEN to R.string.bot_log_filter_screen,
        BotEventLog.CAT_RETURN to R.string.bot_log_filter_return,
        BotEventLog.CAT_PAUSE to R.string.bot_log_filter_pause,
    )

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleReload()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_bot_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        toggleCategory = view.findViewById(R.id.toggleBotLogCategory)
        tvPageInfo = view.findViewById(R.id.tvBotLogPageInfo)
        btnLoadMore = view.findViewById(R.id.btnBotLogLoadMore)

        val rv = view.findViewById<RecyclerView>(R.id.rvBotLog)
        rv.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        rv.isVerticalScrollBarEnabled = true
        rv.setHasFixedSize(false)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0) followTail = false
                else if (!recyclerView.canScrollVertically(1)) followTail = true
            }
        })
        adapter = BotLogAdapter(
            isBurstExpanded = { id -> id in expandedBurstIds },
            onExpandBurst = { id ->
                expandedBurstIds.add(id)
                refreshAdapter()
            },
            onCollapseBurst = { id ->
                expandedBurstIds.remove(id)
                refreshAdapter()
            },
        )
        rv.adapter = adapter
        setupCategoryFilters()

        view.findViewById<MaterialButton>(R.id.btnBotLogScrollTop).setOnClickListener {
            followTail = false
            scrollLogToTop()
        }
        view.findViewById<MaterialButton>(R.id.btnBotLogScrollBottom).setOnClickListener {
            followTail = true
            scrollLogToBottom()
        }
        btnLoadMore.setOnClickListener {
            visibleLimit += PAGE_SIZE
            refreshAdapter()
        }

        view.findViewById<MaterialButton>(R.id.btnBotLogClearToday).setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
                .setTitle(R.string.bot_log_clear_today_title)
                .setMessage(R.string.bot_log_clear_today_message)
                .setPositiveButton(R.string.bot_log_clear_today) { _, _ ->
                    BotEventLog.clearToday()
                    expandedBurstIds.clear()
                    visibleLimit = PAGE_SIZE
                    followTail = true
                    scheduleReload()
                    Toast.makeText(requireContext(), R.string.bot_log_cleared_today, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        view.findViewById<MaterialButton>(R.id.btnBotLogClearAll).setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
                .setTitle(R.string.bot_log_clear_all_title)
                .setMessage(R.string.bot_log_clear_all_message)
                .setPositiveButton(R.string.bot_log_clear_all) { _, _ ->
                    BotEventLog.clear()
                    expandedBurstIds.clear()
                    visibleLimit = PAGE_SIZE
                    followTail = true
                    scheduleReload()
                    Toast.makeText(requireContext(), R.string.bot_log_cleared_all, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        scheduleReload()
    }

    private fun setupCategoryFilters() {
        val ctx = requireContext()
        categoryFilters.forEachIndexed { index, (category, labelRes) ->
            val btn = SegmentedToggleStyle.createSegmentButton(ctx, getString(labelRes)).apply {
                id = View.generateViewId()
                tag = category
                isChecked = index == 0
            }
            toggleCategory.addView(btn)
        }
        SegmentedToggleStyle.wireGroup(toggleCategory) { checkedId ->
            val btn = toggleCategory.findViewById<MaterialButton>(checkedId)
            activeCategory = btn?.tag as? String ?: FILTER_ALL
            visibleLimit = PAGE_SIZE
            refreshAdapter()
        }
        toggleCategory.check(toggleCategory.getChildAt(0).id)
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(eventReceiver, IntentFilter(BotEventLog.ACTION_EVENT))
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(eventReceiver)
        mainHandler.removeCallbacks(reloadRunnable)
        super.onStop()
    }

    private fun scheduleReload() {
        if (!isAdded) return
        mainHandler.removeCallbacks(reloadRunnable)
        mainHandler.postDelayed(reloadRunnable, 150)
    }

    private fun reloadLogInternal() {
        if (!isAdded || loading) return
        loading = true
        BotEventLog.loadForUi { items ->
            loading = false
            if (!isAdded) return@loadForUi
            latestEntries = items
            expandedBurstIds.retainAll(
                items.flatMap { entry ->
                    when {
                        BotEventBurstCodec.isBurstBundle(entry) -> listOf(BotEventBurstCodec.burstId(entry))
                        entry.burstGroupId > 0L -> listOf(entry.burstGroupId)
                        else -> emptyList()
                    }
                }.toSet(),
            )
            refreshAdapter()
            val rv = view?.findViewById<RecyclerView>(R.id.rvBotLog) ?: return@loadForUi
            if (followTail) {
                rv.post { scrollLogToBottom() }
            }
        }
    }

    private fun filteredEntries(): List<BotEventEntry> {
        val filtered = when (activeCategory) {
            FILTER_ALL -> latestEntries
            BotEventLog.CAT_BURST -> latestEntries.filter {
                it.burstGroupId > 0L ||
                    BotEventBurstCodec.isBurstBundle(it) ||
                    (it.category == BotEventLog.CAT_BURST && it.burstGroupId == 0L)
            }
            else -> latestEntries.filter {
                it.burstGroupId == 0L &&
                    !BotEventBurstCodec.isBurstBundle(it) &&
                    it.category == activeCategory
            }
        }
        return filtered.take(visibleLimit)
    }

    private fun totalFilteredCount(): Int =
        when (activeCategory) {
            FILTER_ALL -> latestEntries.size
            BotEventLog.CAT_BURST -> latestEntries.count {
                it.burstGroupId > 0L ||
                    BotEventBurstCodec.isBurstBundle(it) ||
                    (it.category == BotEventLog.CAT_BURST && it.burstGroupId == 0L)
            }
            else -> latestEntries.count {
                it.burstGroupId == 0L &&
                    !BotEventBurstCodec.isBurstBundle(it) &&
                    it.category == activeCategory
            }
        }

    private fun refreshAdapter() {
        val shown = filteredEntries()
        val total = totalFilteredCount()
        adapter?.submit(shown, expandedBurstIds)
        tvPageInfo.text = getString(R.string.bot_log_page_info, shown.size, total)
        btnLoadMore.isVisible = total > visibleLimit
        if (followTail) {
            view?.findViewById<RecyclerView>(R.id.rvBotLog)?.post { scrollLogToBottom() }
        }
    }

    private fun scrollLogToTop() {
        val rv = view?.findViewById<RecyclerView>(R.id.rvBotLog) ?: return
        if ((adapter?.itemCount ?: 0) > 0) rv.scrollToPosition(0)
    }

    private fun scrollLogToBottom() {
        val rv = view?.findViewById<RecyclerView>(R.id.rvBotLog) ?: return
        val count = adapter?.itemCount ?: 0
        if (count > 0) rv.scrollToPosition(count - 1)
    }

    private enum class RowKind { NORMAL, BURST_HEADER, BURST_DETAIL }

    private data class BotLogRow(
        val kind: RowKind,
        val entry: BotEventEntry,
        val burstId: Long = 0L,
    )

    private class BotLogAdapter(
        private val isBurstExpanded: (Long) -> Boolean,
        private val onExpandBurst: (Long) -> Unit,
        private val onCollapseBurst: (Long) -> Unit,
    ) : RecyclerView.Adapter<BotLogAdapter.VH>() {

        private val rows = mutableListOf<BotLogRow>()

        fun submit(list: List<BotEventEntry>, expandedIds: Set<Long>) {
            rows.clear()
            rows.addAll(buildRows(list.asReversed(), expandedIds))
            notifyDataSetChanged()
        }

        private fun buildRows(entriesOldestFirst: List<BotEventEntry>, expandedIds: Set<Long>): List<BotLogRow> {
            val out = ArrayList<BotLogRow>(entriesOldestFirst.size)
            var i = 0
            while (i < entriesOldestFirst.size) {
                val entry = entriesOldestFirst[i]
                if (BotEventBurstCodec.isBurstBundle(entry)) {
                    val burstId = BotEventBurstCodec.burstId(entry)
                    val expanded = burstId in expandedIds
                    out.add(BotLogRow(RowKind.BURST_HEADER, entry, burstId))
                    if (expanded) {
                        BotEventBurstCodec.detailLines(entry).forEach { detail ->
                            out.add(BotLogRow(RowKind.BURST_DETAIL, detail, burstId))
                        }
                    }
                    i++
                    continue
                }
                if (entry.burstGroupId > 0L) {
                    val groupId = entry.burstGroupId
                    val groupEntries = ArrayList<BotEventEntry>()
                    while (i < entriesOldestFirst.size) {
                        val current = entriesOldestFirst[i]
                        if (current.burstGroupId != groupId || BotEventBurstCodec.isBurstBundle(current)) break
                        groupEntries.add(current)
                        i++
                    }
                    val expanded = groupId in expandedIds
                    val summary = BotEventEntry(
                        groupId,
                        BotEventLog.CAT_BURST,
                        BotEventLog.buildBurstSummary(groupEntries, groupId),
                    )
                    out.add(BotLogRow(RowKind.BURST_HEADER, summary, groupId))
                    if (expanded) {
                        groupEntries.forEach { detail ->
                            out.add(BotLogRow(RowKind.BURST_DETAIL, detail, groupId))
                        }
                    }
                    continue
                }
                out.add(BotLogRow(RowKind.NORMAL, entry))
                i++
            }
            return out
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bot_log_row, parent, false)
            return VH(v, isBurstExpanded, onExpandBurst, onCollapseBurst)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        class VH(
            itemView: View,
            private val isBurstExpanded: (Long) -> Boolean,
            private val onExpandBurst: (Long) -> Unit,
            private val onCollapseBurst: (Long) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {

            private val tvTime = itemView.findViewById<TextView>(R.id.tvBotLogTime)
            private val tvCategory = itemView.findViewById<TextView>(R.id.tvBotLogCategory)
            private val tvMessage = itemView.findViewById<TextView>(R.id.tvBotLogMessage)
            private val gestureDetector = GestureDetectorCompat(
                itemView.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        val row = boundRow ?: return false
                        if (row.kind == RowKind.BURST_HEADER && !isBurstExpanded(row.burstId)) {
                            onExpandBurst(row.burstId)
                            return true
                        }
                        return false
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        val row = boundRow ?: return false
                        if (row.burstId != 0L && isBurstExpanded(row.burstId)) {
                            onCollapseBurst(row.burstId)
                            return true
                        }
                        return false
                    }
                },
            )

            private var boundRow: BotLogRow? = null
            private val padTop = itemView.paddingTop
            private val padEnd = itemView.paddingEnd
            private val padBottom = itemView.paddingBottom
            private val density = itemView.resources.displayMetrics.density

            init {
                itemView.setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    false
                }
            }

            fun bind(row: BotLogRow) {
                boundRow = row
                val entry = row.entry
                tvTime.text = BotEventLog.formatTime(entry.timestampMs)

                when (row.kind) {
                    RowKind.NORMAL -> {
                        padStart(8)
                        tvCategory.text = entry.category
                        tvMessage.text = entry.message
                    }
                    RowKind.BURST_HEADER -> {
                        padStart(8)
                        tvCategory.text = BotEventLog.CAT_BURST
                        val expanded = isBurstExpanded(row.burstId)
                        val arrow = if (expanded) "▼" else "▶"
                        val hint = if (expanded) " · doble toque para comprimir" else " · toque para ver detalle"
                        val title = if (BotEventBurstCodec.isBurstBundle(entry)) {
                            BotEventBurstCodec.summary(entry)
                        } else {
                            entry.message.ifBlank { "Ráfaga" }
                        }
                        tvMessage.text = "$arrow $title$hint"
                    }
                    RowKind.BURST_DETAIL -> {
                        padStart(24)
                        tvCategory.text = entry.category
                        tvMessage.text = entry.message
                    }
                }
            }

            private fun padStart(dp: Int) {
                itemView.setPaddingRelative(
                    (dp * density).toInt(),
                    padTop,
                    padEnd,
                    padBottom,
                )
            }
        }
    }
}
