package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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
import com.oceanlab.pichix.service.PichixAccessibilityService

class FlexBotLogFragment : Fragment() {

    private var adapter: BotLogAdapter? = null
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadLog()
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
            reloadLog()
        }

        reloadLog()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BotEventLog.ACTION_EVENT)
            addAction(PichixAccessibilityService.OBSERVER_EVENT)
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(eventReceiver, filter)
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(eventReceiver)
        super.onStop()
    }

    private fun reloadLog() {
        val items = BotEventLog.snapshot()
        adapter?.submit(items)
        view?.findViewById<RecyclerView>(R.id.rvBotLog)?.post {
            val count = items.size
            if (count > 0) {
                view?.findViewById<RecyclerView>(R.id.rvBotLog)?.scrollToPosition(count - 1)
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
