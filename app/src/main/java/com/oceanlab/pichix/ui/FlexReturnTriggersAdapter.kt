package com.oceanlab.pichix.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.FlexReturnScreenTrigger

class FlexReturnTriggersAdapter(
    private var triggers: List<FlexReturnScreenTrigger>,
    private val callbacks: Callbacks,
    private val focusRoot: View,
) : RecyclerView.Adapter<FlexReturnTriggersAdapter.Holder>() {

    interface Callbacks {
        fun onToggle(trigger: FlexReturnScreenTrigger, enabled: Boolean)
        fun onEdit(trigger: FlexReturnScreenTrigger)
    }

    fun submit(newTriggers: List<FlexReturnScreenTrigger>) {
        triggers = newTriggers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_return_trigger, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val trigger = triggers[position]
        holder.tvTitle.text = trigger.displayTitle()
        holder.tvHint.text = trigger.matchSummary()
        holder.swEnabled.setOnCheckedChangeListener(null)
        holder.swEnabled.isChecked = trigger.enabled
        holder.swEnabled.setOnCheckedChangeRetainingFocus(focusRoot) { checked ->
            callbacks.onToggle(trigger, checked)
        }
        holder.itemView.setOnClickListener { callbacks.onEdit(trigger) }
    }

    override fun getItemCount(): Int = triggers.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvReturnTriggerTitle)
        val tvHint: TextView = v.findViewById(R.id.tvReturnTriggerHint)
        val swEnabled: SwitchMaterial = v.findViewById(R.id.switchReturnTriggerEnabled)
    }
}
