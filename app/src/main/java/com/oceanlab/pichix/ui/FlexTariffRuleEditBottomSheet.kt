package com.oceanlab.pichix.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexBlockTypeFilter
import com.oceanlab.pichix.data.FlexPayCriteriaMode
import com.oceanlab.pichix.data.FlexStationScope
import com.oceanlab.pichix.data.FlexTariffRule
import com.oceanlab.pichix.data.FlexTariffRulesStore
import com.oceanlab.pichix.data.FlexTariffWeekdays
import com.oceanlab.pichix.util.BlockDurationText
import java.util.UUID

class FlexTariffRuleEditBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onRuleSaved()
    }

    private lateinit var settings: AppSettings
    private var editingRule: FlexTariffRule? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_tariff_rule, container, false)

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val behavior = dialog.behavior
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        val h = resources.displayMetrics.heightPixels
        behavior.maxHeight = (h * 0.92f).toInt()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext())
        val scroll = view.findViewById<NestedScrollView>(R.id.scrollTariffRuleSheet)
        scroll?.preventScrollFocusSteal()

        val ruleId = arguments?.getString(ARG_RULE_ID)
        val all = FlexTariffRulesStore.load(settings)
        editingRule = ruleId?.let { id -> all.firstOrNull { it.id == id } }

        val tvTitle = view.findViewById<TextView>(R.id.tvSheetTitle)
        val etName = view.findViewById<TextInputEditText>(R.id.etRuleName)
        val spinnerScope = view.findViewById<Spinner>(R.id.spinnerRuleScope)
        val layoutPattern = view.findViewById<TextInputLayout>(R.id.layoutStorePattern)
        val etPattern = view.findViewById<TextInputEditText>(R.id.etRuleStorePattern)
        val spinnerType = view.findViewById<Spinner>(R.id.spinnerRuleOrderType)
        val frameTypePreview = view.findViewById<FrameLayout>(R.id.frameBlockTypePreview)
        val ivTypePreview = view.findViewById<ImageView>(R.id.ivBlockTypePreview)
        val spinnerPay = view.findViewById<Spinner>(R.id.spinnerPayMode)
        val layoutPayBlock = view.findViewById<View>(R.id.layoutPayBlock)
        val layoutPayHourly = view.findViewById<TextInputLayout>(R.id.layoutPayHourly)
        val layoutPayManualFixed = view.findViewById<TextInputLayout>(R.id.layoutPayManualFixed)
        val tvPayManualAny = view.findViewById<TextView>(R.id.tvPayManualAny)
        val etPriceMin = view.findViewById<TextInputEditText>(R.id.etRulePriceMin)
        val etPriceMax = view.findViewById<TextInputEditText>(R.id.etRulePriceMax)
        val etMinHourly = view.findViewById<TextInputEditText>(R.id.etRuleMinRate)
        val etManualPrice = view.findViewById<TextInputEditText>(R.id.etRuleManualPrice)
        val etMinDuration = view.findViewById<TextInputEditText>(R.id.etRuleMinDuration)
        val etMaxDuration = view.findViewById<TextInputEditText>(R.id.etRuleMaxDuration)
        val swBlockStart = view.findViewById<SwitchMaterial>(R.id.switchBlockStartWindow)
        val layoutBlockStart = view.findViewById<View>(R.id.layoutBlockStartWindow)
        val layoutBlockStartFrom = view.findViewById<TextInputLayout>(R.id.layoutBlockStartFrom)
        val layoutBlockStartTo = view.findViewById<TextInputLayout>(R.id.layoutBlockStartTo)
        val etBlockStartFrom = view.findViewById<TextInputEditText>(R.id.etBlockStartFrom)
        val etBlockStartTo = view.findViewById<TextInputEditText>(R.id.etBlockStartTo)
        val etLeadTime = view.findViewById<TextInputEditText>(R.id.etRuleLeadTime)
        val swTime = view.findViewById<SwitchMaterial>(R.id.switchRuleTime)
        val layoutTime = view.findViewById<View>(R.id.layoutRuleTimeFields)
        val layoutRuleTimeStart = view.findViewById<TextInputLayout>(R.id.layoutRuleTimeStart)
        val layoutRuleTimeTo = view.findViewById<TextInputLayout>(R.id.layoutRuleTimeTo)
        val etTimeStart = view.findViewById<TextInputEditText>(R.id.etRuleTimeStart)
        val etTimeEnd = view.findViewById<TextInputEditText>(R.id.etRuleTimeEnd)
        val chipKeywords = view.findViewById<ChipGroup>(R.id.chipGroupRuleKeywords)
        val etAddKeyword = view.findViewById<TextInputEditText>(R.id.etRuleAddKeyword)
        val btnAddKeyword = view.findViewById<MaterialButton>(R.id.btnRuleAddKeyword)
        val swWeekdays = view.findViewById<SwitchMaterial>(R.id.switchRuleWeekdays)
        val chipGroupWeekdays = view.findViewById<ChipGroup>(R.id.chipGroupRuleWeekdays)
        val tvPreview = view.findViewById<TextView>(R.id.tvRulePreview)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnRuleCancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnRuleSave)

        chipKeywords.preventChipGroupFocusSteal()
        chipGroupWeekdays.preventChipGroupFocusSteal()

        tvTitle.text = if (editingRule == null) "Nueva regla Flex" else "Editar regla Flex"

        spinnerScope.adapter = spinnerAdapter(FlexStationScope.entries.map { it.label })
        spinnerType.adapter = spinnerAdapter(FlexBlockTypeFilter.entries.map { it.label })
        spinnerPay.adapter = spinnerAdapter(FlexPayCriteriaMode.entries.map { it.label })

        val rule = editingRule ?: FlexTariffRule()
        etName.setText(rule.name)
        spinnerScope.setSelection(
            safeEnumIndex(FlexStationScope.entries.indexOf(rule.stationScope), FlexStationScope.entries.size),
            false,
        )
        etPattern.setText(rule.stationPattern)
        spinnerType.setSelection(
            safeEnumIndex(FlexBlockTypeFilter.entries.indexOf(rule.blockType), FlexBlockTypeFilter.entries.size),
            false,
        )
        spinnerPay.setSelection(
            safeEnumIndex(FlexPayCriteriaMode.entries.indexOf(rule.payMode), FlexPayCriteriaMode.entries.size),
            false,
        )
        etPriceMin.setText("%.0f".format(rule.priceMin))
        etPriceMax.setText(rule.priceMax?.let { "%.0f".format(it) } ?: "")
        etMinHourly.setText("%.0f".format(rule.minHourlyRate))
        etManualPrice.setText("%.0f".format(rule.priceMin))
        etMinDuration.setText(rule.minDurationHours?.let { BlockDurationText.formatFromHours(it) } ?: "")
        etMaxDuration.setText(rule.maxDurationHours?.let { BlockDurationText.formatFromHours(it) } ?: "")
        swBlockStart.isChecked = rule.blockStartFilterEnabled
        etBlockStartFrom.setText(TimePickerFieldHelper.minutesOfDayToHHmm(rule.blockStartFromMinutes))
        etBlockStartTo.setText(TimePickerFieldHelper.minutesOfDayToHHmm(rule.blockStartToMinutes))
        etLeadTime.setText(
            rule.minLeadTimeMinutes?.let { TimePickerFieldHelper.durationMinutesToHHmm(it) } ?: "",
        )
        swTime.isChecked = rule.timeEnabled
        etTimeStart.setText(TimePickerFieldHelper.minutesOfDayToHHmm(rule.timeStartMinutes))
        etTimeEnd.setText(TimePickerFieldHelper.minutesOfDayToHHmm(rule.timeEndMinutes))
        swWeekdays.isChecked = rule.weekdaysEnabled

        val weekdayChips = mutableMapOf<Int, Chip>()
        FlexTariffWeekdays.ORDERED.forEach { (day, label) ->
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isChecked = rule.allowedWeekdays.contains(day)
                isFocusable = false
            }
            weekdayChips[day] = chip
            chipGroupWeekdays.addView(chip)
        }

        fun scopeSelected() = FlexStationScope.entries[
            spinnerScope.selectedItemPosition.coerceIn(0, FlexStationScope.entries.size - 1)
        ]
        fun typeSelected() = FlexBlockTypeFilter.entries[
            spinnerType.selectedItemPosition.coerceIn(0, FlexBlockTypeFilter.entries.size - 1)
        ]
        fun paySelected() = FlexPayCriteriaMode.entries[
            spinnerPay.selectedItemPosition.coerceIn(0, FlexPayCriteriaMode.entries.size - 1)
        ]

        fun updateBlockTypePreview() {
            val draft = FlexTariffRule(blockType = typeSelected())
            val ui = draft.blockTypeUi()
            frameTypePreview?.setBackgroundResource(ui.badgeBackgroundRes)
            ivTypePreview?.setImageResource(draft.blockTypeIconRes())
            ivTypePreview?.setColorFilter(iconTintColor(ui.iconTintColorRes))
        }

        fun updateScopeUi() {
            when (scopeSelected()) {
                FlexStationScope.GLOBAL -> layoutPattern.visibility = View.GONE
                FlexStationScope.SPECIFIC -> {
                    layoutPattern.visibility = View.VISIBLE
                    layoutPattern.hint = "Estación (nombre o fragmento)"
                }
                FlexStationScope.GROUP -> {
                    layoutPattern.visibility = View.VISIBLE
                    layoutPattern.hint = "Grupo: estaciones separadas por coma"
                }
            }
        }

        fun updatePayUi() {
            layoutPayBlock.visibility = View.GONE
            layoutPayHourly.visibility = View.GONE
            layoutPayManualFixed.visibility = View.GONE
            tvPayManualAny.visibility = View.GONE
            when (paySelected()) {
                FlexPayCriteriaMode.BLOCK_PAY -> layoutPayBlock.visibility = View.VISIBLE
                FlexPayCriteriaMode.HOURLY_PAY -> layoutPayHourly.visibility = View.VISIBLE
                FlexPayCriteriaMode.MANUAL_FIXED -> layoutPayManualFixed.visibility = View.VISIBLE
                FlexPayCriteriaMode.MANUAL_ANY -> tvPayManualAny.visibility = View.VISIBLE
            }
        }

        fun buildDraft(): FlexTariffRule {
            val payMode = paySelected()
            val manualPrice = etManualPrice.text.toString().toDoubleOrNull() ?: 90.0
            val blockMin = etPriceMin.text.toString().toDoubleOrNull() ?: manualPrice
            val leadRaw = etLeadTime.text.toString().trim()
            return FlexTariffRule(
                id = editingRule?.id ?: UUID.randomUUID().toString(),
                enabled = editingRule?.enabled ?: true,
                sortOrder = editingRule?.sortOrder ?: all.size,
                name = etName.text.toString().trim(),
                stationScope = scopeSelected(),
                stationPattern = etPattern.text.toString().trim(),
                blockType = typeSelected(),
                payMode = payMode,
                priceMin = when (payMode) {
                    FlexPayCriteriaMode.MANUAL_FIXED -> manualPrice
                    else -> blockMin
                },
                priceMax = etPriceMax.text.toString().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
                minHourlyRate = etMinHourly.text.toString().toDoubleOrNull() ?: 23.0,
                blockStartFilterEnabled = swBlockStart.isChecked,
                blockStartFromMinutes = TimePickerFieldHelper.parseToMinutesOfDay(etBlockStartFrom.text.toString())
                    ?: 6 * 60,
                blockStartToMinutes = TimePickerFieldHelper.parseToMinutesOfDay(etBlockStartTo.text.toString())
                    ?: 22 * 60,
                minLeadTimeMinutes = leadRaw.takeIf { it.isNotEmpty() }
                    ?.let { TimePickerFieldHelper.parseDurationMinutes(it) },
                timeEnabled = swTime.isChecked,
                timeStartMinutes = TimePickerFieldHelper.parseToMinutesOfDay(etTimeStart.text.toString())
                    ?: 6 * 60,
                timeEndMinutes = TimePickerFieldHelper.parseToMinutesOfDay(etTimeEnd.text.toString())
                    ?: 22 * 60,
                minDurationHours = etMinDuration.text.toString().trim().takeIf { it.isNotEmpty() }
                    ?.let { BlockDurationText.parseToHours(it) },
                maxDurationHours = etMaxDuration.text.toString().trim().takeIf { it.isNotEmpty() }
                    ?.let { BlockDurationText.parseToHours(it) },
                excludedKeywords = ChipInputHelper.collectChips(chipKeywords),
                weekdaysEnabled = swWeekdays.isChecked,
                allowedWeekdays = if (swWeekdays.isChecked) {
                    weekdayChips.filter { it.value.isChecked }.keys
                } else {
                    emptySet()
                },
            )
        }

        fun refreshPreview() {
            val block: () -> Unit = {
                tvPreview.text = buildDraft().previewText()
                updateScopeUi()
                updatePayUi()
                updateBlockTypePreview()
            }
            if (scroll != null) scroll.runRetainingScrollAndFocus(block) else block()
        }

        TimePickerFieldHelper.attachClockPicker(
            this, etBlockStartFrom, layoutBlockStartFrom, "Hora inicio desde", ::refreshPreview,
        )
        TimePickerFieldHelper.attachClockPicker(
            this, etBlockStartTo, layoutBlockStartTo, "Hora inicio hasta", ::refreshPreview,
        )
        TimePickerFieldHelper.attachClockPicker(
            this, etTimeStart, layoutRuleTimeStart, "Horario desde", ::refreshPreview,
        )
        TimePickerFieldHelper.attachClockPicker(
            this, etTimeEnd, layoutRuleTimeTo, "Horario hasta", ::refreshPreview,
        )

        rule.excludedKeywords.forEach { kw ->
            ChipInputHelper.addChip(requireContext(), chipKeywords, kw, onChanged = { refreshPreview() })
        }

        weekdayChips.values.forEach { it.setOnCheckedChangeListener { _, _ -> refreshPreview() } }
        swWeekdays.setOnCheckedChangeListener { _, checked ->
            chipGroupWeekdays.visibility = if (checked) View.VISIBLE else View.GONE
            refreshPreview()
        }
        chipGroupWeekdays.visibility = if (swWeekdays.isChecked) View.VISIBLE else View.GONE

        swBlockStart.setOnCheckedChangeListener { _, on ->
            layoutBlockStart.visibility = if (on) View.VISIBLE else View.GONE
            refreshPreview()
        }
        layoutBlockStart.visibility = if (swBlockStart.isChecked) View.VISIBLE else View.GONE

        swTime.setOnCheckedChangeListener { _, on ->
            layoutTime.visibility = if (on) View.VISIBLE else View.GONE
            refreshPreview()
        }
        layoutTime.visibility = if (swTime.isChecked) View.VISIBLE else View.GONE

        spinnerScope.onItemSelectedListener = simpleItemSelected { refreshPreview() }
        spinnerType.onItemSelectedListener = simpleItemSelected { refreshPreview() }
        spinnerPay.onItemSelectedListener = simpleItemSelected { refreshPreview() }

        listOf(
            etName, etPattern, etPriceMin, etPriceMax, etMinHourly, etManualPrice,
            etMinDuration, etMaxDuration, etBlockStartFrom, etBlockStartTo, etLeadTime,
            etTimeStart, etTimeEnd,
        ).forEach { it.onPreviewTextChanged(::refreshPreview) }

        btnAddKeyword.setOnClickListener {
            val txt = etAddKeyword.text.toString().trim()
            if (txt.isEmpty()) return@setOnClickListener
            ChipInputHelper.addChip(requireContext(), chipKeywords, txt, onChanged = { refreshPreview() }, etAddKeyword)
            etAddKeyword.setText("")
            refreshPreview()
        }

        refreshPreview()

        btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        btnSave.setOnClickListener {
            val draft = buildDraft()
            if (draft.payMode == FlexPayCriteriaMode.BLOCK_PAY &&
                draft.priceMax != null && draft.priceMax < draft.priceMin
            ) {
                Toast.makeText(context, "Pago máximo debe ser ≥ mínimo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (draft.stationScope != FlexStationScope.GLOBAL && draft.stationPattern.isBlank()) {
                Toast.makeText(context, "Indica estación o usa Global", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updated = all.toMutableList()
            val idx = updated.indexOfFirst { it.id == draft.id }
            if (idx >= 0) updated[idx] = draft else updated.add(draft)
            FlexTariffRulesStore.save(settings, updated)
            ruleEditListener()?.onRuleSaved()
            dismissAllowingStateLoss()
        }
    }

    private fun spinnerAdapter(labels: List<String>) =
        ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun ruleEditListener(): Listener? =
        (parentFragment as? Listener)
            ?: (parentFragment?.parentFragment as? Listener)

    private fun iconTintColor(resId: Int): Int =
        try {
            ContextCompat.getColor(requireContext(), resId)
        } catch (_: Exception) {
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        }

    private fun safeEnumIndex(index: Int, size: Int): Int =
        if (size <= 0) 0 else if (index < 0) 0 else index.coerceIn(0, size - 1)

    companion object {
        private const val ARG_RULE_ID = "rule_id"

        fun newInstance(ruleId: String?) = FlexTariffRuleEditBottomSheet().apply {
            arguments = Bundle().apply { ruleId?.let { putString(ARG_RULE_ID, it) } }
        }
    }
}

private fun TextInputEditText.onPreviewTextChanged(onChange: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = onChange()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    })
}

private fun simpleItemSelected(onSelect: () -> Unit) =
    object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
