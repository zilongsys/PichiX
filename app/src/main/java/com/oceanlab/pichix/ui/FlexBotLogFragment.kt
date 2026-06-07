package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.BotEventEntry
import com.oceanlab.pichix.data.BotEventLog

class FlexBotLogFragment : Fragment() {

    private var adapter: BotLogAdapter? = null
    private var loading = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { reloadLogInternal() }

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
        adapter = BotLogAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        rv.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btnClearBotLog).setOnClickListener {
            BotEventLog.clear()
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
            adapter?.submit(items)
            val rv = view?.findViewById<RecyclerView>(R.id.rvBotLog) ?: return@loadForUi
            rv.post {
                if (items.isNotEmpty()) rv.scrollToPosition(items.size - 1)
            }
        }
    }

    private class BotLogAdapter : RecyclerView.Adapter<BotLogAdapter.VH>() {
        private val items = mutableListOf<BotEventEntry>()

        fun submit(list: List<BotEventEntry>) {
            items.clear()
            items.addAll(list.asReversed())
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bot_log_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTime = itemView.findViewById<TextView>(R.id.tvBotLogTime)
            private val tvCategory = itemView.findViewById<TextView>(R.id.tvBotLogCategory)
            private val tvMessage = itemView.findViewById<TextView>(R.id.tvBotLogMessage)

            fun bind(entry: BotEventEntry) {
                tvTime.text = BotEventLog.formatTime(entry.timestampMs)
                tvCategory.text = entry.category
                tvMessage.text = entry.message
            }
        }
    }
}
