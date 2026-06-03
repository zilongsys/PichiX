package com.oceanlab.pichix.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexBlockTypeFilter
import com.oceanlab.pichix.data.FlexClassicTariffImporter
import com.oceanlab.pichix.data.FlexPayCriteriaMode
import com.oceanlab.pichix.data.FlexStationScope
import com.oceanlab.pichix.data.FlexTariffRule
import com.oceanlab.pichix.data.FlexTariffRulesStore
import com.oceanlab.pichix.service.PichixAccessibilityService

class FlexTarifasRulesFragment : Fragment(), FlexTariffRuleEditBottomSheet.Listener {

    private lateinit var settings: AppSettings
    private lateinit var adapter: FlexTariffRulesAdapter
    private var rules: MutableList<FlexTariffRule> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tarifas_rules, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext())
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerRules)
        val tvEmpty = view.findViewById<TextView>(R.id.tvRulesEmpty)
        val tvActive = view.findViewById<TextView>(R.id.tvRulesActiveCount)

        adapter = FlexTariffRulesAdapter(rules, object : FlexTariffRulesAdapter.Callbacks {
            override fun onToggleEnabled(rule: FlexTariffRule, enabled: Boolean) {
                updateRule(rule.copy(enabled = enabled), reloadList = false)
            }
            override fun onEdit(rule: FlexTariffRule) = openEditor(rule.id)
            override fun onDelete(rule: FlexTariffRule) = confirmDelete(rule)
            override fun onSetPriority(rule: FlexTariffRule, currentPosition: Int) =
                showPriorityDialog(rule, currentPosition)
            override fun onRulePressed(rule: FlexTariffRule, currentPosition: Int) =
                showRuleActionsDialog(rule, currentPosition)
        })
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btnAddRule).setOnClickListener { openEditor(null) }
        view.findViewById<MaterialButton>(R.id.btnImportClassic).setOnClickListener { confirmImportClassic() }
        setupRulesHeaderExpand(view)
        reloadRules(tvEmpty, tvActive)
    }

    private fun setupRulesHeaderExpand(view: View) {
        val header = view.findViewById<TextView>(R.id.tvRulesHeader)
        val expanded = view.findViewById<View>(R.id.layoutRulesHeaderExpanded)
        var open = false
        header.setOnClickListener {
            open = !open
            expanded.visibility = if (open) View.VISIBLE else View.GONE
            header.setCompoundDrawablesWithIntrinsicBounds(
                0, 0,
                if (open) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                0,
            )
        }
    }

    private fun confirmImportClassic() {
        val imported = FlexClassicTariffImporter.importFromClassic(settings)
        if (rules.isEmpty()) {
            applyImport(imported, replace = true)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Importar tarifa rápida")
            .setMessage(
                "Se creará ${imported.size} regla con mín. bloque, $/h y hora de inicio actuales.\n\n" +
                    "¿Qué hacer con las ${rules.size} reglas existentes?"
            )
            .setPositiveButton("Reemplazar") { _, _ -> applyImport(imported, replace = true) }
            .setNeutralButton("Añadir al final") { _, _ -> applyImport(imported, replace = false) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyImport(imported: List<FlexTariffRule>, replace: Boolean) {
        rules = if (replace) imported.toMutableList() else (rules + imported).toMutableList()
        persist()
        Toast.makeText(
            requireContext(),
            FlexClassicTariffImporter.summaryMessage(imported.size),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onRuleSaved() {
        view?.let { v ->
            reloadRules(
                v.findViewById(R.id.tvRulesEmpty),
                v.findViewById(R.id.tvRulesActiveCount),
            )
        }
        PichixAccessibilityService.syncEngine(requireContext())
    }

    private fun reloadRules(tvEmpty: TextView, tvActive: TextView) {
        rules = FlexTariffRulesStore.load(settings).toMutableList()
        adapter.submit(rules.toList())
        tvActive.text = "${rules.count { it.enabled }} activas"
        tvEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateRule(updated: FlexTariffRule, reloadList: Boolean = true) {
        val idx = rules.indexOfFirst { it.id == updated.id }
        if (idx < 0) return
        rules[idx] = updated
        FlexTariffRulesStore.save(settings, rules)
        if (reloadList) {
            persist()
        } else {
            adapter.updateRule(idx, updated)
            view?.findViewById<TextView>(R.id.tvRulesActiveCount)?.text =
                "${rules.count { it.enabled }} activas"
            (activity as? MainActivity)?.markDirty(2)
        }
        PichixAccessibilityService.syncEngine(requireContext())
    }

    private fun persist() {
        FlexTariffRulesStore.save(settings, rules)
        view?.let { v ->
            reloadRules(
                v.findViewById(R.id.tvRulesEmpty),
                v.findViewById(R.id.tvRulesActiveCount),
            )
        }
        (activity as? MainActivity)?.markDirty(2)
        PichixAccessibilityService.syncEngine(requireContext())
    }

    private fun showPriorityDialog(rule: FlexTariffRule, currentPosition: Int) {
        if (rules.size <= 1) {
            Toast.makeText(context, "Solo hay una regla (prioridad 1)", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((currentPosition + 1).toString())
            setSelection(text.length)
            hint = "1 - ${rules.size}"
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        input.setPadding(pad, pad / 2, pad, pad / 2)

        AlertDialog.Builder(requireContext())
            .setTitle("Prioridad de la regla")
            .setMessage("\"${rule.displayTitle()}\"\n\n1 = se evalúa primero. Actual: ${currentPosition + 1}.")
            .setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                val newPos = input.text.toString().toIntOrNull()
                if (newPos == null || newPos !in 1..rules.size) {
                    Toast.makeText(context, "Prioridad entre 1 y ${rules.size}", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                setRulePriority(rule, newPos - 1)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setRulePriority(rule: FlexTariffRule, targetIndex: Int) {
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx < 0 || idx == targetIndex) return
        val item = rules.removeAt(idx)
        rules.add(targetIndex, item)
        persist()
    }

    private fun showRuleActionsDialog(rule: FlexTariffRule, currentPosition: Int) {
        val title = rule.displayTitle()
        AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
            .setTitle(title)
            .setItems(
                arrayOf(
                    getString(R.string.tariff_rule_action_duplicate),
                    getString(R.string.tariff_rule_action_edit),
                    getString(R.string.tariff_rule_action_priority),
                    getString(R.string.tariff_rule_action_delete),
                ),
            ) { _, which ->
                when (which) {
                    0 -> duplicateRule(rule, currentPosition)
                    1 -> openEditor(rule.id)
                    2 -> showPriorityDialog(rule, currentPosition)
                    3 -> confirmDelete(rule)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun duplicateRule(rule: FlexTariffRule, currentPosition: Int) {
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx < 0) return
        val copy = rule.duplicate()
        rules.add(idx + 1, copy)
        persist()
        Toast.makeText(
            requireContext(),
            getString(R.string.tariff_rule_duplicated, copy.displayTitle()),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun confirmDelete(rule: FlexTariffRule) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar regla")
            .setMessage("¿Eliminar \"${rule.displayTitle()}\"?")
            .setPositiveButton("Eliminar") { _, _ ->
                rules.removeAll { it.id == rule.id }
                persist()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openEditor(ruleId: String?) {
        val tag = "edit_flex_rule"
        (childFragmentManager.findFragmentByTag(tag) as? FlexTariffRuleEditBottomSheet)?.dismissAllowingStateLoss()
        if (childFragmentManager.isStateSaved) return
        FlexTariffRuleEditBottomSheet.newInstance(ruleId)
            .show(childFragmentManager, tag)
    }

    fun ensureStarterRulesIfEmpty() {
        if (FlexTariffRulesStore.load(settings).isNotEmpty()) return
        val starter = listOf(
            FlexTariffRule(
                name = "Global Flex (ejemplo)",
                stationScope = FlexStationScope.GLOBAL,
                blockType = FlexBlockTypeFilter.ALL,
                payMode = FlexPayCriteriaMode.BLOCK_PAY,
                priceMin = settings.flexMinBlockPay.toDouble(),
                minHourlyRate = settings.flexMinHourlyRate.toDouble(),
                blockStartFilterEnabled = true,
                blockStartFromMinutes = settings.flexMinStartHour * 60,
                blockStartToMinutes = 22 * 60,
            ),
        )
        FlexTariffRulesStore.save(settings, starter)
        onRuleSaved()
    }
}
