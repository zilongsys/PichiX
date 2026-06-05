package com.oceanlab.pichix.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexReturnScreenTrigger
import com.oceanlab.pichix.data.FlexReturnTriggersStore
import java.util.UUID

class FlexReturnTriggerEditBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onTriggersChanged()
    }

    private lateinit var settings: AppSettings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_return_trigger, container, false)

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val behavior = dialog.behavior
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext())
        val triggerId = arguments?.getString(ARG_TRIGGER_ID)
        val all = FlexReturnTriggersStore.load(settings).toMutableList()
        val editing = triggerId?.let { id -> all.firstOrNull { it.id == id } }
        val isNew = editing == null

        view.findViewById<TextView>(R.id.tvReturnTriggerSheetTitle).text =
            if (isNew) getString(R.string.config_return_trigger_add_title)
            else getString(R.string.config_return_trigger_edit_title)

        val etLabel = view.findViewById<TextInputEditText>(R.id.etReturnTriggerLabel)
        val etPhrases = view.findViewById<TextInputEditText>(R.id.etReturnTriggerPhrases)
        val toggleMatch = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleReturnTriggerMatch)
        val swIgnoreCase = view.findViewById<SwitchMaterial>(R.id.switchReturnTriggerIgnoreCase)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnReturnTriggerDelete)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnReturnTriggerSave)

        editing?.let { t ->
            etLabel.setText(t.label)
            etPhrases.setText(t.phrases.joinToString("\n"))
            TextMatchUiHelper.setMatchMode(
                toggleMatch,
                t.matchMode,
                R.id.btnReturnTriggerContains,
                R.id.btnReturnTriggerExact,
            )
            swIgnoreCase.isChecked = t.ignoreCase
            btnDelete.visibility = View.VISIBLE
        } ?: run {
            TextMatchUiHelper.setMatchMode(
                toggleMatch,
                AppSettings.TEXT_MATCH_CONTAINS,
                R.id.btnReturnTriggerContains,
                R.id.btnReturnTriggerExact,
            )
            swIgnoreCase.isChecked = true
        }

        btnDelete.setOnClickListener {
            if (editing == null) {
                dismiss()
                return@setOnClickListener
            }
            all.removeAll { it.id == editing.id }
            FlexReturnTriggersStore.save(settings, all)
            (parentFragment as? Listener ?: activity as? Listener)?.onTriggersChanged()
            dismiss()
        }

        btnSave.setOnClickListener {
            val phrases = etPhrases.text?.toString()
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (phrases.isEmpty()) {
                Toast.makeText(requireContext(), R.string.config_return_trigger_phrases_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val saved = FlexReturnScreenTrigger(
                id = editing?.id ?: UUID.randomUUID().toString(),
                enabled = editing?.enabled ?: true,
                label = etLabel.text?.toString()?.trim().orEmpty(),
                phrases = phrases,
                matchMode = TextMatchUiHelper.readMatchMode(toggleMatch, R.id.btnReturnTriggerExact),
                ignoreCase = swIgnoreCase.isChecked,
            )
            if (isNew) all.add(saved) else {
                val idx = all.indexOfFirst { it.id == saved.id }
                if (idx >= 0) all[idx] = saved
            }
            FlexReturnTriggersStore.save(settings, all)
            (parentFragment as? Listener ?: activity as? Listener)?.onTriggersChanged()
            dismiss()
        }
    }

    companion object {
        private const val ARG_TRIGGER_ID = "trigger_id"

        fun newInstance(triggerId: String? = null): FlexReturnTriggerEditBottomSheet =
            FlexReturnTriggerEditBottomSheet().apply {
                arguments = Bundle().apply {
                    if (triggerId != null) putString(ARG_TRIGGER_ID, triggerId)
                }
            }
    }
}
