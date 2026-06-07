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
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.BotEventBurstCodec
import com.oceanlab.pichix.data.BotEventEntry
import com.oceanlab.pichix.data.BotEventLog

class FlexBotLogFragment : Fragment() {

    private var adapter: BotLogAdapter? = null
    private var loading = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { reloadLogInternal() }
    private val expandedBurstIds = mutableSetOf<Long>()
    private var latestEntries = emptyList<BotEventEntry>()

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
        val rv = view.findViewById<RecyclerView>(R.id.rvBotLog)
        adapter = BotLogAdapter(
            isBurstExpanded = { id -> id in expandedBurstIds },
            onExpandBurst = { id ->
                expandedBurstIds.add(id)
                adapter?.submit(latestEntries, expandedBurstIds)
            },
            onCollapseBurst = { id ->
                expandedBurstIds.remove(id)
                adapter?.submit(latestEntries, expandedBurstIds)
            },
        )
        rv.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        rv.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btnClearBotLog).setOnClickListener {
            BotEventLog.clear()
            expandedBurstIds.clear()
            scheduleReload()
        }

        scheduleReload()
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
            expandedBurstIds.retainAll(items.filter { BotEventBurstCodec.isBurstBundle(it) }
                .map { BotEventBurstCodec.burstId(it) }
                .toSet())
            adapter?.submit(items, expandedBurstIds)
            val rv = view?.findViewById<RecyclerView>(R.id.rvBotLog) ?: return@loadForUi
            rv.post {
                val count = adapter?.itemCount ?: 0
                if (count > 0) rv.scrollToPosition(count - 1)
            }
        }
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
            for (entry in entriesOldestFirst) {
                if (BotEventBurstCodec.isBurstBundle(entry)) {
                    val burstId = BotEventBurstCodec.burstId(entry)
                    val expanded = burstId in expandedIds
                    out.add(BotLogRow(RowKind.BURST_HEADER, entry, burstId))
                    if (expanded) {
                        BotEventBurstCodec.detailLines(entry).forEach { detail ->
                            out.add(BotLogRow(RowKind.BURST_DETAIL, detail, burstId))
                        }
                    }
                } else {
                    out.add(BotLogRow(RowKind.NORMAL, entry))
                }
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
                        tvMessage.text = "$arrow ${BotEventBurstCodec.summary(entry)}$hint"
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
