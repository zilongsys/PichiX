package com.oceanlab.pichix.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.MonitorPackages

class FlexConfigFragment : Fragment() {

    private lateinit var settings: AppSettings
    private var tvAccess: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pichix_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext())
        view.setupFormFocus()

        val etPackage = view.findViewById<TextInputEditText>(R.id.etFlexPackage)
        val swShowNames = view.findViewById<SwitchMaterial>(R.id.switchShowCategoryNames)
        tvAccess = view.findViewById(R.id.tvAccessStatus)
        val btnAccess = view.findViewById<MaterialButton>(R.id.btnGoAccess)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveConfig)

        etPackage.setText(settings.monitorPackagesCsv)
        swShowNames.isChecked = settings.showCategoryNames

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        view.findViewById<MaterialButton>(R.id.btnGoNotifications)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnSave.setOnClickListener {
            view.runRetainingFocus {
                settings.monitorPackagesCsv = etPackage.text?.toString()?.trim().orEmpty()
                settings.showCategoryNames = swShowNames.isChecked
                MonitorPackages.notifyReload(requireContext())
                (requireActivity() as MainActivity).apply {
                    applyCategoryUi()
                    clearDirty(1)
                }
                LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(Intent(CategoryUiHelper.ACTION_CATEGORY_UI_CHANGED))
                Toast.makeText(requireContext(), "Configuración guardada", Toast.LENGTH_SHORT).show()
            }
        }

        etPackage.onUserTextChanged(onDirty = { (requireActivity() as MainActivity).markDirty(1) })
        swShowNames.setOnCheckedChangeRetainingFocus(view) { _ ->
            (requireActivity() as MainActivity).markDirty(1)
        }

        refreshAccessibilityStatus()
    }

    fun refreshAccessibilityStatus() {
        val activity = activity as? MainActivity ?: return
        val enabled = activity.isAccessibilityEnabled()
        val ctx = requireContext()
        tvAccess?.text = if (enabled) "✓ Activado" else "✗ No activado"
        tvAccess?.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (enabled) R.color.green_400 else R.color.coral_600
            )
        )
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }
}
