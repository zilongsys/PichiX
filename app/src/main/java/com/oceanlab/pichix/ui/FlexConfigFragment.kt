package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.PichiFileLog
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
    private var suppressReturn2Sync = false
    private var swReturn2: SwitchMaterial? = null

    private val return2Receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MainActivity.RETURN2_SETTING_CHANGED) return
            suppressReturn2Sync = true
            try {
                swReturn2?.isChecked =
                    intent.getBooleanExtra(MainActivity.EXTRA_RETURN2_ENABLED, settings.flexAutoReturnToOffers)
            } finally {
                suppressReturn2Sync = false
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pichix_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext())
        view.setupFormFocus()
        setupExpandableSections(view)

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
        swReturn2 = view.findViewById(R.id.switchReturn2Offers)
        val swAutoAccept = view.findViewById<SwitchMaterial>(R.id.switchFlexAutoAccept)
        val swPauseOver = view.findViewById<SwitchMaterial>(R.id.switchPauseOverClicks)
        val swForeground = view.findViewById<SwitchMaterial>(R.id.switchFlexOnlyForeground)
        val swDebug = view.findViewById<SwitchMaterial>(R.id.switchDebugLog)
        val swFileLog = view.findViewById<SwitchMaterial>(R.id.switchFileLog)
        val etPauseText = view.findViewById<TextInputEditText>(R.id.etPauseOverClicksText)
        val etPauseMinutes = view.findViewById<TextInputEditText>(R.id.etPauseOverClicksMinutes)
        val toggleClickMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleClickMode)
        val layoutBasic = view.findViewById<LinearLayout>(R.id.layoutBasicClick)
        val layoutSmart = view.findViewById<LinearLayout>(R.id.layoutSmartClick)
        val etBasicSec = view.findViewById<TextInputEditText>(R.id.etBasicClickIntervalSec)
        val etSmartMin = view.findViewById<TextInputEditText>(R.id.etSmartClickMinSec)
        val etSmartMax = view.findViewById<TextInputEditText>(R.id.etSmartClickMaxSec)
        val swClickRefresh = view.findViewById<SwitchMaterial>(R.id.switchClickRefresh)
        val swAutoScroll = view.findViewById<SwitchMaterial>(R.id.switchAutoScroll)
        val toggleOfferPick = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleOfferPickMode)
        val toggleOfferRank = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleOfferRank)
        val tvOfferRankLabel = view.findViewById<TextView>(R.id.tvOfferRankLabel)
        val etRefreshBtn = view.findViewById<TextInputEditText>(R.id.etRefreshButtonText)
        val etClickScreen = view.findViewById<TextInputEditText>(R.id.etClickScreenText)
        val toggleRefreshMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleRefreshButtonMatchMode)
        val swRefreshIgnore = view.findViewById<SwitchMaterial>(R.id.switchRefreshButtonIgnoreCase)
        val toggleScreenMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleScreenMatchMode)
        val swScreenIgnore = view.findViewById<SwitchMaterial>(R.id.switchScreenIgnoreCase)
        val togglePauseMode = view.findViewById<MaterialButtonToggleGroup>(R.id.togglePauseMatchMode)
        val swPauseIgnore = view.findViewById<SwitchMaterial>(R.id.switchPauseIgnoreCase)
        tvAccess = view.findViewById(R.id.tvAccessStatus)
        val btnAccess = view.findViewById<MaterialButton>(R.id.btnGoAccess)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveConfig)

        etPackage.setText(settings.monitorPackagesCsv)
        swShowNames.isChecked = settings.showCategoryNames
        swReturn2?.isChecked = settings.flexAutoReturnToOffers
        swAutoAccept.isChecked = settings.flexAutoAccept
        swPauseOver.isChecked = settings.pauseByOverClicksEnabled
        swForeground.isChecked = settings.flexOnlyWhenForeground
        swDebug.isChecked = settings.debugLogEnabled
        swFileLog.isChecked = settings.fileLogEnabled
        etPauseText.setText(settings.pauseByOverClicksMatchText)
        etPauseMinutes.setText(settings.pauseByOverClicksResumeMinutes.toString())
        pauseSoundUri = settings.pauseByOverClicksPauseSoundUri
        resumeSoundUri = settings.pauseByOverClicksResumeSoundUri
        val basicSec = (settings.flexGrabIntervalMs / 1000L).toInt().coerceIn(1, 60)
        etBasicSec.setText(basicSec.toString())
        etSmartMin.setText(settings.flexSmartClickMinSec.toString())
        etSmartMax.setText(settings.flexSmartClickMaxSec.toString())
        swClickRefresh.isChecked = settings.flexClickRefreshEnabled
        swAutoScroll.isChecked = settings.flexAutoScrollEnabled
        toggleOfferPick.check(
            if (settings.usesBestOfferPick()) R.id.btnOfferPickBest else R.id.btnOfferPickFirst,
        )
        setOfferRankToggle(toggleOfferRank, settings.flexOfferRankCriterion)
        fun updateOfferRankVisibility() {
            val best = toggleOfferPick.checkedButtonId == R.id.btnOfferPickBest
            val vis = if (best) View.VISIBLE else View.GONE
            tvOfferRankLabel.visibility = vis
            toggleOfferRank.visibility = vis
        }
        updateOfferRankVisibility()
        etRefreshBtn.setText(settings.flexRefreshButtonText)
        etClickScreen.setText(settings.flexClickScreenText)
        val smartMode = settings.flexClickMode == AppSettings.CLICK_MODE_SMART
        toggleClickMode.check(if (smartMode) R.id.btnClickModeSmart else R.id.btnClickModeBasic)
        updateClickModeVisibility(layoutBasic, layoutSmart, smartMode)
        TextMatchUiHelper.setMatchMode(
            toggleRefreshMode,
            settings.flexRefreshButtonMatchMode,
            R.id.btnRefreshMatchContains,
            R.id.btnRefreshMatchExact,
        )
        swRefreshIgnore.isChecked = settings.flexRefreshButtonIgnoreCase
        TextMatchUiHelper.setMatchMode(
            toggleScreenMode,
            settings.flexClickScreenMatchMode,
            R.id.btnScreenMatchContains,
            R.id.btnScreenMatchExact,
        )
        swScreenIgnore.isChecked = settings.flexClickScreenIgnoreCase
        TextMatchUiHelper.setMatchMode(
            togglePauseMode,
            settings.pauseByOverClicksMatchMode,
            R.id.btnPauseMatchContains,
            R.id.btnPauseMatchExact,
        )
        swPauseIgnore.isChecked = settings.pauseByOverClicksIgnoreCase
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
        view.findViewById<MaterialButton>(R.id.btnShareDiagnosticLogs)?.setOnClickListener {
            shareDiagnosticLogs()
        }

        btnSave.setOnClickListener {
            view.runRetainingFocus {
                persistAllFields(
                    etPackage, swShowNames, swAutoAccept, swPauseOver, swForeground, swDebug, swFileLog,
                    etPauseText, etPauseMinutes, toggleClickMode, etBasicSec, etSmartMin, etSmartMax,
                    swClickRefresh, swAutoScroll, toggleOfferPick, toggleOfferRank,
                    etRefreshBtn, etClickScreen, toggleRefreshMode, swRefreshIgnore,
                    toggleScreenMode, swScreenIgnore, togglePauseMode, swPauseIgnore,
                )
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
        swAutoAccept.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        swReturn2?.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (suppressReturn2Sync) return@setOnCheckedChangeRetainingFocus
            settings.flexAutoReturnToOffers = checked
            MainActivity.notifyReturn2SettingChanged(requireContext(), checked)
            PichixAccessibilityService.syncEngine(requireContext())
            markDirty()
        }
        swPauseOver.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        swForeground.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        swDebug.setOnCheckedChangeRetainingFocus(view) { checked ->
            settings.debugLogEnabled = checked
            markDirty()
        }
        swFileLog.setOnCheckedChangeRetainingFocus(view) { checked ->
            settings.fileLogEnabled = checked
            PichiFileLog.setFileLogEnabled(checked)
            markDirty()
        }
        etPauseText.onUserTextChanged(onDirty = { markDirty() })
        etPauseMinutes.onUserTextChanged(onDirty = { markDirty() })
        toggleClickMode.addOnButtonCheckedRetainingFocus(view) {
            val smart = toggleClickMode.checkedButtonId == R.id.btnClickModeSmart
            updateClickModeVisibility(layoutBasic, layoutSmart, smart)
            markDirty()
        }
        etBasicSec.onUserTextChanged(onDirty = { markDirty() })
        etSmartMin.onUserTextChanged(onDirty = { markDirty() })
        etSmartMax.onUserTextChanged(onDirty = { markDirty() })
        swClickRefresh.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        swAutoScroll.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        toggleOfferPick.addOnButtonCheckedRetainingFocus(view) {
            updateOfferRankVisibility()
            markDirty()
        }
        toggleOfferRank.addOnButtonCheckedRetainingFocus(view) { markDirty() }
        etRefreshBtn.onUserTextChanged(onDirty = { markDirty() })
        etClickScreen.onUserTextChanged(onDirty = { markDirty() })
        toggleRefreshMode.addOnButtonCheckedRetainingFocus(view) { markDirty() }
        swRefreshIgnore.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        toggleScreenMode.addOnButtonCheckedRetainingFocus(view) { markDirty() }
        swScreenIgnore.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        togglePauseMode.addOnButtonCheckedRetainingFocus(view) { markDirty() }
        swPauseIgnore.setOnCheckedChangeRetainingFocus(view) { markDirty() }

        refreshAccessibilityStatus()
    }

    private fun setupExpandableSections(view: View) {
        ConfigSectionBinder.bind(view.findViewById(R.id.headerSectionAmazon), view.findViewById(R.id.sectionAmazon))
        ConfigSectionBinder.bind(view.findViewById(R.id.headerSectionPermisos), view.findViewById(R.id.sectionPermisos))
        ConfigSectionBinder.bind(view.findViewById(R.id.headerSectionAuto), view.findViewById(R.id.sectionAuto))
        ConfigSectionBinder.bind(view.findViewById(R.id.headerSectionClicks), view.findViewById(R.id.sectionClicks), startExpanded = false)
        ConfigSectionBinder.bind(view.findViewById(R.id.headerSectionPause), view.findViewById(R.id.sectionPause), startExpanded = false)
        ConfigSectionBinder.bind(view.findViewById(R.id.headerSectionLog), view.findViewById(R.id.sectionLog), startExpanded = false)
        ConfigSectionBinder.bind(view.findViewById(R.id.headerSectionUi), view.findViewById(R.id.sectionUi))
    }

    private fun updateClickModeVisibility(basic: View, smart: View, smartMode: Boolean) {
        basic.visibility = if (smartMode) View.GONE else View.VISIBLE
        smart.visibility = if (smartMode) View.VISIBLE else View.GONE
    }

    private fun persistAllFields(
        etPackage: TextInputEditText,
        swShowNames: SwitchMaterial,
        swAutoAccept: SwitchMaterial,
        swPauseOver: SwitchMaterial,
        swForeground: SwitchMaterial,
        swDebug: SwitchMaterial,
        swFileLog: SwitchMaterial,
        etPauseText: TextInputEditText,
        etPauseMinutes: TextInputEditText,
        toggleClickMode: MaterialButtonToggleGroup,
        etBasicSec: TextInputEditText,
        etSmartMin: TextInputEditText,
        etSmartMax: TextInputEditText,
        swClickRefresh: SwitchMaterial,
        swAutoScroll: SwitchMaterial,
        toggleOfferPick: MaterialButtonToggleGroup,
        toggleOfferRank: MaterialButtonToggleGroup,
        etRefreshBtn: TextInputEditText,
        etClickScreen: TextInputEditText,
        toggleRefreshMode: MaterialButtonToggleGroup,
        swRefreshIgnore: SwitchMaterial,
        toggleScreenMode: MaterialButtonToggleGroup,
        swScreenIgnore: SwitchMaterial,
        togglePauseMode: MaterialButtonToggleGroup,
        swPauseIgnore: SwitchMaterial,
    ) {
        settings.monitorPackagesCsv = etPackage.text?.toString()?.trim().orEmpty()
        settings.showCategoryNames = swShowNames.isChecked
        settings.flexAutoReturnToOffers = swReturn2?.isChecked == true
        settings.flexAutoAccept = swAutoAccept.isChecked
        settings.pauseByOverClicksEnabled = swPauseOver.isChecked
        settings.flexOnlyWhenForeground = swForeground.isChecked
        settings.debugLogEnabled = swDebug.isChecked
        settings.fileLogEnabled = swFileLog.isChecked
        PichiFileLog.setFileLogEnabled(settings.fileLogEnabled)
        settings.pauseByOverClicksMatchText = etPauseText.text?.toString()?.trim().orEmpty()
        settings.pauseByOverClicksResumeMinutes =
            etPauseMinutes.text?.toString()?.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 5
        settings.pauseByOverClicksPauseSoundUri = pauseSoundUri
        settings.pauseByOverClicksResumeSoundUri = resumeSoundUri
        val smartSelected = toggleClickMode.checkedButtonId == R.id.btnClickModeSmart
        settings.flexClickMode = if (smartSelected) AppSettings.CLICK_MODE_SMART else AppSettings.CLICK_MODE_BASIC
        val sec = etBasicSec.text?.toString()?.toIntOrNull()?.coerceIn(1, 60) ?: 3
        settings.flexGrabIntervalMs = sec * 1000L
        val minSec = etSmartMin.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 1
        val maxSec = etSmartMax.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 6
        settings.flexSmartClickMinSec = minOf(minSec, maxSec)
        settings.flexSmartClickMaxSec = maxOf(minSec, maxSec)
        settings.flexClickRefreshEnabled = swClickRefresh.isChecked
        settings.flexAutoScrollEnabled = swAutoScroll.isChecked
        settings.flexOfferPickMode = if (toggleOfferPick.checkedButtonId == R.id.btnOfferPickBest) {
            AppSettings.OFFER_PICK_BEST
        } else {
            AppSettings.OFFER_PICK_FIRST
        }
        settings.flexOfferRankCriterion = readOfferRankCriterion(toggleOfferRank)
        settings.flexRefreshButtonText =
            etRefreshBtn.text?.toString()?.trim().orEmpty().ifBlank { AppSettings.DEFAULT_REFRESH_BUTTON }
        settings.flexRefreshButtonMatchMode =
            TextMatchUiHelper.readMatchMode(toggleRefreshMode, R.id.btnRefreshMatchExact)
        settings.flexRefreshButtonIgnoreCase = swRefreshIgnore.isChecked
        settings.flexClickScreenText = etClickScreen.text?.toString()?.trim().orEmpty()
        settings.flexClickScreenMatchMode =
            TextMatchUiHelper.readMatchMode(toggleScreenMode, R.id.btnScreenMatchExact)
        settings.flexClickScreenIgnoreCase = swScreenIgnore.isChecked
        settings.pauseByOverClicksMatchMode =
            TextMatchUiHelper.readMatchMode(togglePauseMode, R.id.btnPauseMatchExact)
        settings.pauseByOverClicksIgnoreCase = swPauseIgnore.isChecked
        if (!settings.pauseByOverClicksEnabled) {
            PauseByOverClicksController.cancelScheduledResume()
        }
        MainActivity.notifyReturn2SettingChanged(requireContext(), settings.flexAutoReturnToOffers)
        MonitorPackages.notifyReload(requireContext())
        PichixAccessibilityService.syncEngine(requireContext())
    }

    private fun shareDiagnosticLogs() {
        val ctx = requireContext()
        val files = listOfNotNull(
            PichiFileLog.botLogFileForToday(),
            PichiFileLog.uiLogFileForToday(),
            PichiFileLog.crashFile(),
        ).filter { it.exists() && it.length() > 0L }
        if (files.isEmpty()) {
            Toast.makeText(ctx, "No hay archivos de log para compartir", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>()
        files.forEach { f ->
            uris.add(
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            )
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.config_btn_share_logs)))
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

    private fun setOfferRankToggle(group: MaterialButtonToggleGroup, criterion: String) {
        group.check(
            when (criterion) {
                AppSettings.OFFER_RANK_BLOCK_PAY -> R.id.btnRankBlockPay
                AppSettings.OFFER_RANK_DURATION_MIN -> R.id.btnRankDurationMin
                AppSettings.OFFER_RANK_START_SOONEST -> R.id.btnRankStartSoon
                else -> R.id.btnRankHourly
            },
        )
    }

    private fun readOfferRankCriterion(group: MaterialButtonToggleGroup): String = when (group.checkedButtonId) {
        R.id.btnRankBlockPay -> AppSettings.OFFER_RANK_BLOCK_PAY
        R.id.btnRankDurationMin -> AppSettings.OFFER_RANK_DURATION_MIN
        R.id.btnRankStartSoon -> AppSettings.OFFER_RANK_START_SOONEST
        else -> AppSettings.OFFER_RANK_HOURLY
    }

    fun refreshAccessibilityStatus() {
        val activity = activity as? MainActivity ?: return
        val enabled = activity.isAccessibilityEnabled()
        val ctx = requireContext()
        tvAccess?.text = if (enabled) "✓ Activado" else "✗ No activado"
        tvAccess?.setTextColor(
            ContextCompat.getColor(ctx, if (enabled) R.color.green_400 else R.color.coral_600)
        )
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        swReturn2?.isChecked = settings.flexAutoReturnToOffers
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(return2Receiver, IntentFilter(MainActivity.RETURN2_SETTING_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(return2Receiver)
        } catch (_: Exception) {
        }
    }
}
