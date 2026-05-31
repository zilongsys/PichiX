package com.oceanlab.pichix.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.FlexTariffRule

class FlexTariffRulesAdapter(
    private var rules: List<FlexTariffRule>,
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<FlexTariffRulesAdapter.Holder>() {

    interface Callbacks {
        fun onToggleEnabled(rule: FlexTariffRule, enabled: Boolean)
        fun onEdit(rule: FlexTariffRule)
        fun onDelete(rule: FlexTariffRule)
        fun onSetPriority(rule: FlexTariffRule, currentPosition: Int)
    }

    fun submit(newRules: List<FlexTariffRule>) {
        rules = newRules
        notifyDataSetChanged()
    }

    fun updateRule(index: Int, updated: FlexTariffRule) {
        if (index !in rules.indices) return
        rules = rules.toMutableList().also { it[index] = updated }
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tariff_rule, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = rules[position]
        val ctx = holder.itemView.context
        val typeUi = rule.blockTypeUi()

        holder.tvIndex.text = (position + 1).toString()
        holder.tvTitle.text = rule.cardTitlePart()

        holder.frameOrderIcon.setBackgroundResource(typeUi.badgeBackgroundRes)
        holder.ivOrderIcon.setImageResource(rule.blockTypeIconRes())
        holder.ivOrderIcon.setColorFilter(ContextCompat.getColor(ctx, typeUi.iconTintColorRes))

        val hint = rule.cardBlockHint()
        if (hint != null) {
            holder.tvOrderHint.visibility = View.VISIBLE
            holder.tvOrderHint.text = hint
        } else {
            holder.tvOrderHint.visibility = View.GONE
        }

        holder.tvSchedule.text = rule.scheduleLabel()
        holder.tvPrice.text = rule.payMode.label
        holder.tvRate.text = rule.payCriteriaLabel()
        val extra = rule.secondaryCriteriaLabel() ?: rule.durationLimitLabel() ?: ""
        if (extra.isNotEmpty()) {
            holder.tvMaxMiles.visibility = View.VISIBLE
            holder.tvMaxMiles.text = extra
        } else {
            holder.tvMaxMiles.visibility = View.GONE
        }

        if (rule.excludedKeywords.isNotEmpty()) {
            holder.tvKeywords.text = "⊘ ${rule.excludedKeywordsLabel()}"
            holder.tvKeywords.setTextColor(ContextCompat.getColor(ctx, R.color.red_400))
        } else {
            holder.tvKeywords.text = "—"
            holder.tvKeywords.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
        }

        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.styleWideSwitch()
        holder.switchEnabled.isChecked = rule.enabled
        holder.switchEnabled.setOnCheckedChangeListener { sw, checked ->
            holder.itemView.focusRoot().runRetainingFocus {
                callbacks.onToggleEnabled(rule, checked)
            }
            sw.post { if (sw.isShown && sw.isEnabled) sw.requestFocus() }
        }

        holder.tvIndex.setOnClickListener { callbacks.onSetPriority(rule, position) }
        holder.btnEdit.setOnClickListener { callbacks.onEdit(rule) }
        holder.btnDelete.setOnClickListener { callbacks.onDelete(rule) }
        holder.itemView.alpha = if (rule.enabled) 1f else 0.55f
    }

    override fun getItemCount(): Int = rules.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val tvIndex: TextView = v.findViewById(R.id.tvRuleIndex)
        val frameOrderIcon: FrameLayout = v.findViewById(R.id.frameRuleOrderIcon)
        val ivOrderIcon: ImageView = v.findViewById(R.id.ivRuleOrderIcon)
        val tvTitle: TextView = v.findViewById(R.id.tvRuleTitle)
        val tvOrderHint: TextView = v.findViewById(R.id.tvRuleOrderHint)
        val tvSchedule: TextView = v.findViewById(R.id.tvRuleSchedule)
        val tvPrice: TextView = v.findViewById(R.id.tvRulePrice)
        val tvRate: TextView = v.findViewById(R.id.tvRuleRate)
        val tvMaxMiles: TextView = v.findViewById(R.id.tvRuleMaxMiles)
        val tvKeywords: TextView = v.findViewById(R.id.tvRuleKeywords)
        val switchEnabled: SwitchMaterial = v.findViewById(R.id.switchRuleEnabled)
        val btnEdit: ImageButton = v.findViewById(R.id.btnRuleEdit)
        val btnDelete: ImageButton = v.findViewById(R.id.btnRuleDelete)
    }
}
