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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.service.PauseByOverClicksController
import com.oceanlab.pichix.service.PichixAccessibilityService
import com.oceanlab.pichix.util.SoundPickerHelper
import com.oceanlab.pichix.util.SoundUriLabel

class FlexConfigFragment : Fragment() {

    private lateinit var settings: AppSettings
    private var tvAccess: TextView? = null
    private var pauseSoundUri: String = ""
    private var resumeSoundUri: String = ""
    private lateinit var pauseSoundPicker: SoundPickerHelper
    private lateinit var resumeSoundPicker: SoundPickerHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pichix_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext())
        view.setupFormFocus()

        pauseSoundPicker = SoundPickerHelper(this) { uri ->
            pauseSoundUri = uri
            refreshSoundLabels(view)
            (requireActivity() as MainActivity).markDirty(1)
        }
        resumeSoundPicker = SoundPickerHelper(this) { uri ->
            resumeSoundUri = uri
            refreshSoundLabels(view)
            (requireActivity() as MainActivity).markDirty(1)
        }

        val etPackage = view.findViewById<TextInputEditText>(R.id.etFlexPackage)
        val swShowNames = view.findViewById<SwitchMaterial>(R.id.switchShowCategoryNames)
        val swReturn2 = view.findViewById<SwitchMaterial>(R.id.switchReturn2Offers)
        val swPauseOver = view.findViewById<SwitchMaterial>(R.id.switchPauseOverClicks)
        val etPauseText = view.findViewById<TextInputEditText>(R.id.etPauseOverClicksText)
        val etPauseMinutes = view.findViewById<TextInputEditText>(R.id.etPauseOverClicksMinutes)
        val toggleClickMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleClickMode)
        val etBasicMs = view.findViewById<TextInputEditText>(R.id.etBasicClickIntervalMs)
        val etSmartMin = view.findViewById<TextInputEditText>(R.id.etSmartClickMinSec)
        val etSmartMax = view.findViewById<TextInputEditText>(R.id.etSmartClickMaxSec)
        val etRefreshBtn = view.findViewById<TextInputEditText>(R.id.etRefreshButtonText)
        val etClickScreen = view.findViewById<TextInputEditText>(R.id.etClickScreenText)
        tvAccess = view.findViewById(R.id.tvAccessStatus)
        val btnAccess = view.findViewById<MaterialButton>(R.id.btnGoAccess)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveConfig)

        etPackage.setText(settings.monitorPackagesCsv)
        swShowNames.isChecked = settings.showCategoryNames
        swReturn2.isChecked = settings.flexAutoReturnToOffers
        swPauseOver.isChecked = settings.pauseByOverClicksEnabled
        etPauseText.setText(settings.pauseByOverClicksMatchText)
        etPauseMinutes.setText(settings.pauseByOverClicksResumeMinutes.toString())
        pauseSoundUri = settings.pauseByOverClicksPauseSoundUri
        resumeSoundUri = settings.pauseByOverClicksResumeSoundUri
        etBasicMs.setText(settings.flexGrabIntervalMs.toString())
        etSmartMin.setText(settings.flexSmartClickMinSec.toString())
        etSmartMax.setText(settings.flexSmartClickMaxSec.toString())
        etRefreshBtn.setText(settings.flexRefreshButtonText)
        etClickScreen.setText(settings.flexClickScreenText)
        toggleClickMode.check(
            if (settings.flexClickMode == AppSettings.CLICK_MODE_SMART) {
                R.id.btnClickModeSmart
            } else {
                R.id.btnClickModeBasic
            }
        )
        refreshSoundLabels(view)

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        view.findViewById<MaterialButton>(R.id.btnGoNotifications)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        view.findViewById<MaterialButton>(R.id.btnPickPauseSound)?.setOnClickListener {
            pauseSoundPicker.pickRingtone(pauseSoundUri)
        }
        view.findViewById<MaterialButton>(R.id.btnPickPauseAudioFile)?.setOnClickListener {
            pauseSoundPicker.pickAudioFromDevice()
        }
        view.findViewById<MaterialButton>(R.id.btnPickResumeSound)?.setOnClickListener {
            resumeSoundPicker.pickRingtone(resumeSoundUri)
        }
        view.findViewById<MaterialButton>(R.id.btnPickResumeAudioFile)?.setOnClickListener {
            resumeSoundPicker.pickAudioFromDevice()
        }

        btnSave.setOnClickListener {
            view.runRetainingFocus {
                settings.monitorPackagesCsv = etPackage.text?.toString()?.trim().orEmpty()
                settings.showCategoryNames = swShowNames.isChecked
                settings.flexAutoReturnToOffers = swReturn2.isChecked
                settings.pauseByOverClicksEnabled = swPauseOver.isChecked
                settings.pauseByOverClicksMatchText = etPauseText.text?.toString()?.trim().orEmpty()
                settings.pauseByOverClicksResumeMinutes =
                    etPauseMinutes.text?.toString()?.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 5
                settings.pauseByOverClicksPauseSoundUri = pauseSoundUri
                settings.pauseByOverClicksResumeSoundUri = resumeSoundUri
                val smartSelected = toggleClickMode.checkedButtonId == R.id.btnClickModeSmart
                settings.flexClickMode = if (smartSelected) {
                    AppSettings.CLICK_MODE_SMART
                } else {
                    AppSettings.CLICK_MODE_BASIC
                }
                settings.flexGrabIntervalMs =
                    etBasicMs.text?.toString()?.toLongOrNull()?.coerceIn(800L, 60_000L) ?: 2500L
                val minSec = etSmartMin.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 1
                val maxSec = etSmartMax.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 6
                settings.flexSmartClickMinSec = minOf(minSec, maxSec)
                settings.flexSmartClickMaxSec = maxOf(minSec, maxSec)
                settings.flexRefreshButtonText =
                    etRefreshBtn.text?.toString()?.trim().orEmpty()
                        .ifBlank { AppSettings.DEFAULT_REFRESH_BUTTON }
                settings.flexClickScreenText = etClickScreen.text?.toString()?.trim().orEmpty()
                if (!settings.pauseByOverClicksEnabled) {
                    PauseByOverClicksController.cancelScheduledResume()
                }
                MonitorPackages.notifyReload(requireContext())
                PichixAccessibilityService.syncEngine(requireContext())
                (requireActivity() as MainActivity).apply {
                    applyCategoryUi()
                    clearDirty(1)
                }
                LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(Intent(CategoryUiHelper.ACTION_CATEGORY_UI_CHANGED))
                Toast.makeText(requireContext(), "Configuración guardada", Toast.LENGTH_SHORT).show()
            }
        }

        val markDirty: () -> Unit = { (requireActivity() as MainActivity).markDirty(1) }
        etPackage.onUserTextChanged(onDirty = { markDirty() })
        swShowNames.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        swReturn2.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        swPauseOver.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        etPauseText.onUserTextChanged(onDirty = { markDirty() })
        etPauseMinutes.onUserTextChanged(onDirty = { markDirty() })
        toggleClickMode.addOnButtonCheckedListener { _, _, _ -> markDirty() }
        etBasicMs.onUserTextChanged(onDirty = { markDirty() })
        etSmartMin.onUserTextChanged(onDirty = { markDirty() })
        etSmartMax.onUserTextChanged(onDirty = { markDirty() })
        etRefreshBtn.onUserTextChanged(onDirty = { markDirty() })
        etClickScreen.onUserTextChanged(onDirty = { markDirty() })

        refreshAccessibilityStatus()
    }

    private fun refreshSoundLabels(view: View) {
        val ctx = requireContext()
        view.findViewById<TextView>(R.id.tvPauseSoundLabel)?.text =
            getString(R.string.config_pause_sound_pause_label) + ": " +
                SoundUriLabel.label(ctx, pauseSoundUri)
        view.findViewById<TextView>(R.id.tvResumeSoundLabel)?.text =
            getString(R.string.config_pause_sound_resume_label) + ": " +
                SoundUriLabel.label(ctx, resumeSoundUri)
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
