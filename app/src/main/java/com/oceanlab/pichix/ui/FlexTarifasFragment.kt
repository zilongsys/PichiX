package com.oceanlab.pichix.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.service.PichixAccessibilityService

class FlexTarifasFragment : Fragment() {

    private lateinit var settings: AppSettings
    private var showingDetailed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tarifas_flex, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext())
        view.setupFormFocus()

        val toggle = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleTariffMode)
        val tvModeInfo = view.findViewById<TextView>(R.id.tvModeInfo)
        val btnToggleModeInfo = view.findViewById<TextView>(R.id.btnToggleModeInfo)

        showingDetailed = settings.usesFlexDetailedTariff()
        if (showingDetailed) toggle.check(R.id.btnTariffDetailed) else toggle.check(R.id.btnTariffClassic)
        SegmentedToggleStyle.wireGroup(toggle, view) { checkedId ->
            val detailed = checkedId == R.id.btnTariffDetailed
            if (detailed == showingDetailed) return@wireGroup
            if (!isAdded) return@wireGroup
            settings.flexTariffMode = if (detailed) {
                AppSettings.TARIFF_MODE_DETAILED
            } else {
                AppSettings.TARIFF_MODE_CLASSIC
            }
            showingDetailed = detailed
            updateModeUi(view, detailed)
            showChildFragment(detailed)
            if (detailed) {
                childFragmentManager.findFragmentByTag(TAG_RULES)
                    ?.let { it as? FlexTarifasRulesFragment }
                    ?.ensureStarterRulesIfEmpty()
            }
            PichixAccessibilityService.syncEngine(requireContext())
            (activity as? MainActivity)?.markDirty(2)
        }
        updateModeUi(view, showingDetailed)
        applyModeInfoVisibility(tvModeInfo, btnToggleModeInfo, settings.showFlexTariffModeInfo)
        showChildFragment(showingDetailed)

        view.findViewById<View>(R.id.layoutTariffModeHeader).setOnClickListener {
            settings.showFlexTariffModeInfo = !settings.showFlexTariffModeInfo
            applyModeInfoVisibility(tvModeInfo, btnToggleModeInfo, settings.showFlexTariffModeInfo)
        }

    }

    private fun applyModeInfoVisibility(info: TextView, toggle: TextView, visible: Boolean) {
        info.visibility = if (visible) View.VISIBLE else View.GONE
        toggle.text = if (visible) "Ocultar info" else "Mostrar info"
    }

    private fun updateModeUi(view: View, detailed: Boolean) {
        view.findViewById<TextView>(R.id.tvTariffModeActive).text =
            if (detailed) "Reglas" else "Rápida"
        view.findViewById<TextView>(R.id.tvModeInfo).text = if (detailed) {
            "Reglas por estación, tipo de bloque, pago y $/h. Prioridad 1 = primero; la primera que coincide decide."
        } else {
            "Criterios globales del grabber (mín. $/h, pago bloque, hora inicio). Pulsa Guardar al terminar."
        }
    }

    private fun showChildFragment(detailed: Boolean) {
        val fm = childFragmentManager
        val tx = fm.beginTransaction()
        val classic = fm.findFragmentByTag(TAG_CLASSIC)
        val rules = fm.findFragmentByTag(TAG_RULES)
        if (detailed) {
            if (rules == null) {
                tx.add(R.id.tarifasContainer, FlexTarifasRulesFragment(), TAG_RULES)
            } else {
                tx.show(rules)
            }
            classic?.let { tx.hide(it) }
            tx.runOnCommit {
                (fm.findFragmentByTag(TAG_RULES) as? FlexTarifasRulesFragment)?.ensureStarterRulesIfEmpty()
            }
        } else {
            if (classic == null) {
                tx.add(R.id.tarifasContainer, FlexTarifasClassicFragment(), TAG_CLASSIC)
            } else {
                tx.show(classic)
            }
            rules?.let { tx.hide(it) }
        }
        if (fm.isStateSaved) {
            tx.commitAllowingStateLoss()
        } else {
            tx.commit()
        }
    }

    companion object {
        private const val TAG_CLASSIC = "flex_tarifas_classic"
        private const val TAG_RULES = "flex_tarifas_rules"
    }
}
