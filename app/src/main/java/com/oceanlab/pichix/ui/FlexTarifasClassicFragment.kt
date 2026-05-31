package com.oceanlab.pichix.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.service.PichixAccessibilityService

/** Tarifa rápida (Terminator-Grabber global). */
class FlexTarifasClassicFragment : Fragment() {

    private lateinit var settings: AppSettings

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_pichix_tarifas, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext())
        view.setupFormFocus()

        val etMinHourly = view.findViewById<TextInputEditText>(R.id.etMinHourly)
        val etMinBlock = view.findViewById<TextInputEditText>(R.id.etMinBlock)
        val etMinStart = view.findViewById<TextInputEditText>(R.id.etMinStartHour)
        val swCancelBad = view.findViewById<SwitchMaterial>(R.id.switchCancelBadBlocks)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveTarifas)

        etMinHourly.setText(settings.flexMinHourlyRate.toString())
        etMinBlock.setText(settings.flexMinBlockPay.toInt().toString())
        etMinStart.setText(settings.flexMinStartHour.toString())
        swCancelBad.isChecked = settings.flexCancelBadBlocks

        fun markDirty() = (requireActivity() as MainActivity).markDirty(2)

        etMinHourly.onUserTextChanged(onDirty = ::markDirty)
        etMinBlock.onUserTextChanged(onDirty = ::markDirty)
        etMinStart.onUserTextChanged(onDirty = ::markDirty)
        swCancelBad.setOnCheckedChangeRetainingFocus(view) { markDirty() }

        btnSave.setOnClickListener {
            view.runRetainingFocus {
                settings.flexMinHourlyRate = etMinHourly.text?.toString()?.toFloatOrNull() ?: 23f
                settings.flexMinBlockPay = etMinBlock.text?.toString()?.toFloatOrNull() ?: 119f
                settings.flexMinStartHour = etMinStart.text?.toString()?.toIntOrNull() ?: 6
                settings.flexCancelBadBlocks = swCancelBad.isChecked
                (requireActivity() as MainActivity).clearDirty(2)
                PichixAccessibilityService.syncEngine(requireContext())
                Toast.makeText(requireContext(), "Tarifa rápida guardada", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
