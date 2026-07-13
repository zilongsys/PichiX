package com.oceanlab.pichix.ui

import android.Manifest
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.FlexReturnScreenTrigger
import com.oceanlab.pichix.data.FlexReturnTriggersStore
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.PichiFileLog
import com.oceanlab.pichix.service.OverlayService
import com.oceanlab.pichix.service.PauseByOverClicksController
import com.oceanlab.pichix.service.PichixAccessibilityService
import com.oceanlab.pichix.util.OverlayPermissionHelper
import com.oceanlab.pichix.util.CallOnBlockHelper
import com.oceanlab.pichix.util.AlertManager
import com.oceanlab.pichix.util.PermissionStatusHelper
import com.oceanlab.pichix.util.SoundPickerHelper
import com.oceanlab.pichix.util.SoundUriLabel

class FlexConfigFragment : Fragment(), FlexReturnTriggerEditBottomSheet.Listener {

    private lateinit var settings: AppSettings
    private var returnTriggers: MutableList<FlexReturnScreenTrigger> = mutableListOf()
    private var returnTriggersAdapter: FlexReturnTriggersAdapter? = null
    private var tvAccess: TextView? = null
    private var tvNotificationAccess: TextView? = null
    private var tvOverlayPermission: TextView? = null
    private var pauseSoundUri: String = ""
    private var resumeSoundUri: String = ""
    private var offerClickSoundUri: String = ""
    private var mismatchSoundUri: String = ""
    private lateinit var pauseSoundPicker: SoundPickerHelper
    private lateinit var resumeSoundPicker: SoundPickerHelper
    private lateinit var offerClickSoundPicker: SoundPickerHelper
    private lateinit var mismatchSoundPicker: SoundPickerHelper
    private var suppressReturn2Sync = false
    private var suppressAutoAcceptSync = false
    private var suppressAutoPersist = false
    /** Evita markDirty / persist mientras se rellena el formulario desde disco. */
    private var suppressUiEvents = false
    private var formBoundFromDisk = false
    private var swReturn2: SwitchMaterial? = null
    private var swAutoAccept: SwitchMaterial? = null
    private lateinit var configScroll: ScrollView
    private var tvPermissionBanner: TextView? = null
    private var tvSaveDirty: TextView? = null
    private var configRootView: View? = null

    private val callPhonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val view = configRootView ?: return@registerForActivityResult
        val swCall = view.findViewById<SwitchMaterial>(R.id.switchCallOnBlock)
        if (granted) {
            swCall.isChecked = true
            settings.callOnBlockEnabled = true
            updateCallOnBlockVisibility(view, true)
            (activity as? MainActivity)?.markDirty(1)
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.config_call_on_block_permission),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

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

    private val autoAcceptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MainActivity.AUTO_ACCEPT_SETTING_CHANGED) return
            suppressAutoAcceptSync = true
            try {
                swAutoAccept?.isChecked =
                    intent.getBooleanExtra(MainActivity.EXTRA_AUTO_ACCEPT_ENABLED, settings.flexAutoAccept)
            } finally {
                suppressAutoAcceptSync = false
            }
        }
    }

    private val configImportedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MainActivity.CONFIG_IMPORTED) return
            if (!isAdded || configRootView == null) return
            reloadConfigFieldsFromSettings()
            (activity as? MainActivity)?.clearDirty(1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(requireContext())
        pauseSoundPicker = SoundPickerHelper(this) { uri ->
            pauseSoundUri = uri
            onPauseOrResumeSoundPicked()
        }
        resumeSoundPicker = SoundPickerHelper(this) { uri ->
            resumeSoundUri = uri
            onPauseOrResumeSoundPicked()
        }
        offerClickSoundPicker = SoundPickerHelper(this) { uri ->
            offerClickSoundUri = uri
            onOfferClickSoundPicked()
        }
        mismatchSoundPicker = SoundPickerHelper(this) { uri ->
            mismatchSoundUri = uri
            onMismatchSoundPicked()
        }
    }

    private fun onMismatchSoundPicked() {
        val view = configRootView ?: return
        if (::configScroll.isInitialized) {
            configScroll.runRetainingScrollAndFocus { refreshMismatchSoundLabel(view) }
        } else {
            refreshMismatchSoundLabel(view)
        }
        (activity as? MainActivity)?.markDirty(1)
    }

    private fun onOfferClickSoundPicked() {
        val view = configRootView ?: return
        if (::configScroll.isInitialized) {
            configScroll.runRetainingScrollAndFocus { refreshOfferClickSoundLabel(view) }
        } else {
            refreshOfferClickSoundLabel(view)
        }
        (activity as? MainActivity)?.markDirty(1)
    }

    private fun onPauseOrResumeSoundPicked() {
        val view = configRootView ?: return
        if (::configScroll.isInitialized) {
            configScroll.runRetainingScrollAndFocus { refreshSoundLabels(view) }
        } else {
            refreshSoundLabels(view)
        }
        (activity as? MainActivity)?.markDirty(1)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pichix_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        suppressAutoPersist = true
        formBoundFromDisk = false
        settings = AppSettings(requireContext())
        configRootView = view
        configScroll = view.findViewById(R.id.configScroll)
        tvSaveDirty = view.findViewById(R.id.tvConfigSaveDirty)
        tvPermissionBanner = view.findViewById(R.id.tvConfigPermissionBanner)
        configScroll.setupFormFocus()
        setupExpandableSections(view, configScroll)
        setupPermissionBanner(view, configScroll)
        setupConfigFooterNote(view, configScroll)
        setupAmazonHint(view, configScroll)
        setupOverlayHints(view, configScroll)
        setupAutomationHints(view, configScroll)
        setupBotClickHints(view, configScroll)
        setupClickScreenHints(view, configScroll)
        setupPauseHints(view, configScroll)
        setupLogHints(view, configScroll)

        val etPackage = view.findViewById<TextInputEditText>(R.id.etFlexPackage)
        val swShowNames = view.findViewById<SwitchMaterial>(R.id.switchShowCategoryNames)
        swReturn2 = view.findViewById(R.id.switchReturn2Offers)
        swAutoAccept = view.findViewById(R.id.switchFlexAutoAccept)
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
        val etReturnStepMin = view.findViewById<TextInputEditText>(R.id.etReturnStepMinSec)
        val etReturnStepMax = view.findViewById<TextInputEditText>(R.id.etReturnStepMaxSec)
        val etReturnCooldown = view.findViewById<TextInputEditText>(R.id.etReturnDetectCooldownSec)
        val swClickRefresh = view.findViewById<SwitchMaterial>(R.id.switchClickRefresh)
        val swReanalyzeAfterRefresh = view.findViewById<SwitchMaterial>(R.id.switchReanalyzeAfterRefresh)
        val layoutReanalyzeAfterRefresh = view.findViewById<View>(R.id.layoutReanalyzeAfterRefresh)
        val tvReanalyzeAfterRefreshHint = view.findViewById<View>(R.id.tvReanalyzeAfterRefreshHint)
        val swBurstClick = view.findViewById<SwitchMaterial>(R.id.switchBurstClick)
        val layoutBurstClick = view.findViewById<LinearLayout>(R.id.layoutBurstClick)
        val etBurstMin = view.findViewById<TextInputEditText>(R.id.etBurstIntervalMinMin)
        val etBurstMax = view.findViewById<TextInputEditText>(R.id.etBurstIntervalMaxMin)
        val etBurstClickMs = view.findViewById<TextInputEditText>(R.id.etBurstClickIntervalMs)
        val etBurstDurationMin = view.findViewById<TextInputEditText>(R.id.etBurstDurationMinSec)
        val etBurstDurationMax = view.findViewById<TextInputEditText>(R.id.etBurstDurationMaxSec)
        val swAutoScroll = view.findViewById<SwitchMaterial>(R.id.switchAutoScroll)
        val toggleOfferPick = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleOfferPickMode)
        val toggleOfferRank = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleOfferRank)
        val layoutOfferRank = view.findViewById<LinearLayout>(R.id.layoutOfferRank)
        val etRefreshBtn = view.findViewById<TextInputEditText>(R.id.etRefreshButtonText)
        val etClickScreen = view.findViewById<TextInputEditText>(R.id.etClickScreenText)
        val toggleRefreshMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleRefreshButtonMatchMode)
        val swRefreshIgnore = view.findViewById<SwitchMaterial>(R.id.switchRefreshButtonIgnoreCase)
        val toggleScreenMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleScreenMatchMode)
        val swScreenIgnore = view.findViewById<SwitchMaterial>(R.id.switchScreenIgnoreCase)
        val togglePauseMode = view.findViewById<MaterialButtonToggleGroup>(R.id.togglePauseMatchMode)
        val swPauseIgnore = view.findViewById<SwitchMaterial>(R.id.switchPauseIgnoreCase)
        tvAccess = view.findViewById(R.id.tvAccessStatus)
        tvNotificationAccess = view.findViewById(R.id.tvNotificationAccessStatus)
        tvOverlayPermission = view.findViewById(R.id.tvOverlayPermissionStatus)
        val btnAccess = view.findViewById<MaterialButton>(R.id.btnGoAccess)
        val swOverlayOnOff = view.findViewById<SwitchMaterial>(R.id.switchOverlayOnOff)
        val swOverlayMotorPause = view.findViewById<SwitchMaterial>(R.id.switchOverlayMotorPause)
        val swOverlayTestReturn = view.findViewById<SwitchMaterial>(R.id.switchOverlayTestReturn)
        val btnGoOverlay = view.findViewById<MaterialButton>(R.id.btnGoOverlay)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveConfig)

        etPackage.setText(settings.monitorPackagesCsv)
        swShowNames.isChecked = settings.showCategoryNames
        swReturn2?.isChecked = settings.flexAutoReturnToOffers
        etReturnStepMin.setText(settings.flexReturnStepMinSec.toString())
        etReturnStepMax.setText(settings.flexReturnStepMaxSec.toString())
        etReturnCooldown.setText(settings.flexReturnDetectCooldownSec.toString())
        swAutoAccept?.isChecked = settings.flexAutoAccept
        view.findViewById<SwitchMaterial>(R.id.switchContinueOnTakeMiss).isChecked =
            settings.flexContinueOnTakeMiss
        view.findViewById<SwitchMaterial>(R.id.switchPauseAfterAccept).isChecked =
            settings.autoPauseAfterAccept
        swPauseOver.isChecked = settings.pauseByOverClicksEnabled
        swForeground.isChecked = settings.flexOnlyWhenForeground
        swOverlayOnOff.isChecked = settings.overlayEnabled
        swOverlayMotorPause.isChecked = settings.overlayMotorPauseFabEnabled
        swOverlayTestReturn.isChecked = settings.overlayTestReturnEnabled
        swDebug.isChecked = settings.debugLogEnabled
        swFileLog.isChecked = settings.fileLogEnabled
        etPauseText.setText(settings.pauseByOverClicksMatchText)
        etPauseMinutes.setText(settings.pauseByOverClicksResumeMinutes.toString())
        pauseSoundUri = settings.pauseByOverClicksPauseSoundUri
        resumeSoundUri = settings.pauseByOverClicksResumeSoundUri
        offerClickSoundUri = settings.offerClickSoundUri
        mismatchSoundUri = settings.flexDetailMismatchSoundUri
        val basicSec = (settings.flexGrabIntervalMs / 1000L).toInt().coerceIn(1, 60)
        etBasicSec.setText(basicSec.toString())
        etSmartMin.setText(settings.flexSmartClickMinSec.toString())
        etSmartMax.setText(settings.flexSmartClickMaxSec.toString())
        swClickRefresh.isChecked = settings.flexClickRefreshEnabled
        swReanalyzeAfterRefresh.isChecked = settings.flexReanalyzeAfterRefreshEnabled
        updateReanalyzeAfterRefreshVisibility(
            layoutReanalyzeAfterRefresh,
            tvReanalyzeAfterRefreshHint,
            swClickRefresh.isChecked,
        )
        swBurstClick.isChecked = settings.flexBurstClickEnabled
        etBurstMin.setText(settings.flexBurstIntervalMinMin.toString())
        etBurstMax.setText(settings.flexBurstIntervalMaxMin.toString())
        etBurstClickMs.setText(settings.flexBurstClickIntervalMs.toString())
        etBurstDurationMin.setText(settings.flexBurstDurationMinSec.toString())
        etBurstDurationMax.setText(settings.flexBurstDurationMaxSec.toString())
        updateBurstVisibility(layoutBurstClick, swBurstClick.isChecked)
        swAutoScroll.isChecked = settings.flexAutoScrollEnabled
        toggleOfferPick.safeCheck(
            if (settings.usesBestOfferPick()) R.id.btnOfferPickBest else R.id.btnOfferPickFirst,
        )
        setOfferRankToggle(toggleOfferRank, settings.flexOfferRankCriterion)
        fun updateOfferRankVisibility() {
            val best = toggleOfferPick.checkedButtonId == R.id.btnOfferPickBest
            val vis = if (best) View.VISIBLE else View.GONE
            layoutOfferRank.visibility = vis
        }
        updateOfferRankVisibility()
        etRefreshBtn.setText(settings.flexRefreshButtonText)
        etClickScreen.setText(settings.flexClickScreenText)
        val smartMode = settings.flexClickMode == AppSettings.CLICK_MODE_SMART
        toggleClickMode.safeCheck(if (smartMode) R.id.btnClickModeSmart else R.id.btnClickModeBasic)
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
        bindCallOnBlockFields(view)
        refreshSoundLabels(view)
        refreshOfferClickSoundLabel(view)
        refreshMismatchSoundLabel(view)

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnGoOverlay.setOnClickListener {
            OverlayPermissionHelper.openOverlaySettings(requireContext())
        }
        val markDirty: () -> Unit = {
            if (!suppressUiEvents) {
                (activity as? MainActivity)?.markDirty(1)
                refreshSaveFooter()
            }
        }
        val applyOverlayFab: (Boolean) -> Unit = { checked ->
            if (checked && !OverlayPermissionHelper.canDrawOverlays(requireContext())) {
                OverlayPermissionHelper.openOverlaySettings(requireContext())
                Toast.makeText(
                    requireContext(),
                    "Concede «Mostrar sobre otras apps» para los botones flotantes",
                    Toast.LENGTH_LONG,
                ).show()
            }
            OverlayService.sync(requireContext())
        }
        swOverlayOnOff.setOnCheckedChangeRetainingFocus(view) { checked ->
            settings.overlayEnabled = checked
            applyOverlayFab(checked)
            markDirty()
        }
        swOverlayMotorPause.setOnCheckedChangeRetainingFocus(view) { checked ->
            settings.overlayMotorPauseFabEnabled = checked
            applyOverlayFab(checked)
            markDirty()
        }
        swOverlayTestReturn.setOnCheckedChangeRetainingFocus(view) { checked ->
            settings.overlayTestReturnEnabled = checked
            applyOverlayFab(checked)
            markDirty()
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
        setupOfferClickSoundControls(view, markDirty)
        setupDetailMismatchControls(view, markDirty)
        view.findViewById<MaterialButton>(R.id.btnShareDiagnosticLogs)?.setOnClickListener {
            shareDiagnosticLogs()
        }

        btnSave.setOnClickListener {
            configScroll.runRetainingScrollAndFocus {
                if (!saveAllFieldsToSettings()) return@runRetainingScrollAndFocus
                (requireActivity() as MainActivity).apply {
                    applyCategoryUi()
                    clearDirty(1)
                }
                refreshSaveFooter()
                LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(Intent(CategoryUiHelper.ACTION_CATEGORY_UI_CHANGED))
                Toast.makeText(requireContext(), "Configuración guardada", Toast.LENGTH_SHORT).show()
            }
        }

        etPackage.onUserTextChanged(onDirty = { markDirty() })
        swShowNames.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        swAutoAccept?.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (suppressAutoAcceptSync) return@setOnCheckedChangeRetainingFocus
            settings.flexAutoAccept = checked
            MainActivity.notifyAutoAcceptSettingChanged(requireContext(), checked)
            markDirty()
        }
        view.findViewById<SwitchMaterial>(R.id.switchContinueOnTakeMiss)
            .setOnCheckedChangeRetainingFocus(view) { checked ->
                settings.flexContinueOnTakeMiss = checked
                markDirty()
            }
        view.findViewById<SwitchMaterial>(R.id.switchPauseAfterAccept)
            .setOnCheckedChangeRetainingFocus(view) { checked ->
                settings.autoPauseAfterAccept = checked
                markDirty()
            }
        swReturn2?.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (suppressReturn2Sync) return@setOnCheckedChangeRetainingFocus
            settings.flexAutoReturnToOffers = checked
            MainActivity.notifyReturn2SettingChanged(requireContext(), checked)
            PichixAccessibilityService.syncEngine(requireContext())
            markDirty()
        }
        setupCallOnBlockControls(view, markDirty)
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
        val applyReturnTiming: () -> Unit = {
            if (!suppressUiEvents) {
                persistReturnTimingSettings(etReturnStepMin, etReturnStepMax, etReturnCooldown)
                markDirty()
            }
        }
        etReturnStepMin.onUserTextChanged(onDirty = { applyReturnTiming() })
        etReturnStepMax.onUserTextChanged(onDirty = { applyReturnTiming() })
        etReturnCooldown.onUserTextChanged(onDirty = { applyReturnTiming() })
        val applyClickMotor: () -> Unit = {
            if (!suppressUiEvents) {
                persistClickMotorSettings(
                    toggleClickMode, etBasicSec, etSmartMin, etSmartMax, swClickRefresh,
                    swReanalyzeAfterRefresh, swBurstClick, etBurstMin, etBurstMax, etBurstClickMs,
                    etBurstDurationMin, etBurstDurationMax,
                    etRefreshBtn, etClickScreen, toggleRefreshMode, swRefreshIgnore,
                    toggleScreenMode, swScreenIgnore,
                )
                markDirty()
            }
        }
        swBurstClick.setOnCheckedChangeRetainingFocus(view) { checked ->
            updateBurstVisibility(layoutBurstClick, checked)
            applyClickMotor()
        }
        toggleClickMode.addOnButtonCheckedRetainingFocus(view) {
            val smart = toggleClickMode.checkedButtonId == R.id.btnClickModeSmart
            updateClickModeVisibility(layoutBasic, layoutSmart, smart)
            applyClickMotor()
        }
        etBasicSec.onUserTextChanged(onDirty = { applyClickMotor() })
        etSmartMin.onUserTextChanged(onDirty = { applyClickMotor() })
        etSmartMax.onUserTextChanged(onDirty = { applyClickMotor() })
        swClickRefresh.setOnCheckedChangeRetainingFocus(view) { checked ->
            updateReanalyzeAfterRefreshVisibility(
                layoutReanalyzeAfterRefresh,
                tvReanalyzeAfterRefreshHint,
                checked,
            )
            applyClickMotor()
        }
        swReanalyzeAfterRefresh.setOnCheckedChangeRetainingFocus(view) { applyClickMotor() }
        swAutoScroll.setOnCheckedChangeRetainingFocus(view) { markDirty() }
        toggleOfferPick.addOnButtonCheckedRetainingFocus(view) {
            updateOfferRankVisibility()
            markDirty()
        }
        toggleOfferRank.addOnButtonCheckedRetainingFocus(view) { markDirty() }
        etBurstMin.onUserTextChanged(onDirty = { applyClickMotor() })
        etBurstMax.onUserTextChanged(onDirty = { applyClickMotor() })
        etBurstClickMs.onUserTextChanged(onDirty = { applyClickMotor() })
        etBurstDurationMin.onUserTextChanged(onDirty = { applyClickMotor() })
        etBurstDurationMax.onUserTextChanged(onDirty = { applyClickMotor() })
        etRefreshBtn.onUserTextChanged(onDirty = { applyClickMotor() })
        etClickScreen.onUserTextChanged(onDirty = { applyClickMotor() })
        toggleRefreshMode.addOnButtonCheckedRetainingFocus(view) { applyClickMotor() }
        swRefreshIgnore.setOnCheckedChangeRetainingFocus(view) { applyClickMotor() }
        toggleScreenMode.addOnButtonCheckedRetainingFocus(view) { applyClickMotor() }
        swScreenIgnore.setOnCheckedChangeRetainingFocus(view) { applyClickMotor() }
        togglePauseMode.addOnButtonCheckedRetainingFocus(view) { markDirty() }
        swPauseIgnore.setOnCheckedChangeRetainingFocus(view) { markDirty() }

        setupReturnTriggers(view, configScroll)
        refreshPermissionStatuses()
        refreshSaveFooter()
        formBoundFromDisk = true
        suppressAutoPersist = false
        suppressUiEvents = false
    }

    private fun setupReturnTriggers(view: View, scrollHost: ScrollView) {
        if (!settings.hasReturnTriggersConfigured()) {
            FlexReturnTriggersStore.save(settings, FlexReturnTriggersStore.defaultTriggers())
        }
        returnTriggers = FlexReturnTriggersStore.load(settings).toMutableList()
        val rv = view.findViewById<RecyclerView>(R.id.rvReturnTriggers)
        rv.isFocusable = false
        rv.isFocusableInTouchMode = false
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        returnTriggersAdapter = FlexReturnTriggersAdapter(
            returnTriggers,
            object : FlexReturnTriggersAdapter.Callbacks {
                override fun onToggle(trigger: FlexReturnScreenTrigger, enabled: Boolean) {
                    val idx = returnTriggers.indexOfFirst { it.id == trigger.id }
                    if (idx < 0) return
                    returnTriggers[idx] = trigger.copy(enabled = enabled)
                    FlexReturnTriggersStore.save(settings, returnTriggers)
                    PichixAccessibilityService.syncEngine(requireContext())
                    (requireActivity() as MainActivity).markDirty(1)
                }

                override fun onEdit(trigger: FlexReturnScreenTrigger) {
                    FlexReturnTriggerEditBottomSheet.newInstance(trigger.id)
                        .show(childFragmentManager, "return_trigger_edit")
                }
            },
            configScroll,
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = returnTriggersAdapter
        view.findViewById<MaterialButton>(R.id.btnAddReturnTrigger)?.setOnClickListener {
            FlexReturnTriggerEditBottomSheet.newInstance()
                .show(childFragmentManager, "return_trigger_add")
        }
        view.findViewById<MaterialButton>(R.id.btnResetReturnTriggers)?.setOnClickListener {
            scrollHost.runRetainingScrollAndFocus {
                returnTriggers = FlexReturnTriggersStore.defaultTriggers().toMutableList()
                persistReturnTriggers()
                Toast.makeText(requireContext(), "Disparadores restaurados", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistReturnTriggers() {
        FlexReturnTriggersStore.save(settings, returnTriggers)
        returnTriggersAdapter?.submit(returnTriggers.toList())
        PichixAccessibilityService.syncEngine(requireContext())
        (requireActivity() as MainActivity).markDirty(1)
    }

    override fun onTriggersChanged() {
        configScroll.runRetainingScrollAndFocus {
            returnTriggers = FlexReturnTriggersStore.load(settings).toMutableList()
            returnTriggersAdapter?.submit(returnTriggers.toList())
            PichixAccessibilityService.syncEngine(requireContext())
            (requireActivity() as MainActivity).markDirty(1)
        }
    }

    private fun setupExpandableSections(view: View, scrollHost: ScrollView) {
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionPermisos),
            view.findViewById(R.id.sectionPermisos),
            scrollHost,
            sectionKey = "permisos",
            startExpanded = true,
        )
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionAmazon),
            view.findViewById(R.id.sectionAmazon),
            scrollHost,
            sectionKey = "amazon",
            startExpanded = false,
        )
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionOverlay),
            view.findViewById(R.id.sectionOverlay),
            scrollHost,
            sectionKey = "overlay",
            startExpanded = true,
        )
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionClickRhythm),
            view.findViewById(R.id.sectionClickRhythm),
            scrollHost,
            sectionKey = "click_rhythm",
            startExpanded = false,
        )
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionAuto),
            view.findViewById(R.id.sectionAuto),
            scrollHost,
            sectionKey = "flex_behavior",
            startExpanded = false,
        )
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionClickScreen),
            view.findViewById(R.id.sectionClickScreen),
            scrollHost,
            sectionKey = "click_screen",
            startExpanded = false,
        )
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionPause),
            view.findViewById(R.id.sectionPause),
            scrollHost,
            sectionKey = "pause",
            startExpanded = false,
        )
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionLog),
            view.findViewById(R.id.sectionLog),
            scrollHost,
            sectionKey = "log",
            startExpanded = false,
        )
        ConfigSectionBinder.bind(
            view.findViewById(R.id.headerSectionUi),
            view.findViewById(R.id.sectionUi),
            scrollHost,
            sectionKey = "ui",
            startExpanded = false,
        )
    }

    private fun setupPermissionBanner(view: View, scrollHost: ScrollView) {
        val headerPermisos = view.findViewById<TextView>(R.id.headerSectionPermisos)
        val sectionPermisos = view.findViewById<View>(R.id.sectionPermisos)
        tvPermissionBanner?.preventCollapsibleHeaderFocusSteal()
        tvPermissionBanner?.setOnClickListener {
            ConfigSectionBinder.ensureExpanded(headerPermisos, sectionPermisos, scrollHost, "permisos")
            scrollHost.post {
                val content = scrollHost.getChildAt(0) ?: return@post
                scrollHost.smoothScrollTo(0, contentOffsetTop(headerPermisos, content))
            }
        }
    }

    private fun setupAmazonHint(view: View, scrollHost: ScrollView) {
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerAmazonPackageHint),
            view.findViewById(R.id.tvAmazonPackageHintToggle),
            view.findViewById(R.id.tvAmazonPackageHint),
            settings,
            "amazon_package",
            scrollHost,
        )
    }

    private fun setupOverlayHints(view: View, scrollHost: ScrollView) {
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerOverlayOnOffHint),
            view.findViewById(R.id.tvOverlayOnOffHintToggle),
            view.findViewById(R.id.tvOverlayOnOffHint),
            settings,
            "overlay_onoff",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerOverlayPauseHint),
            view.findViewById(R.id.tvOverlayPauseHintToggle),
            view.findViewById(R.id.tvOverlayPauseHint),
            settings,
            "overlay_pause",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerOverlayTestReturnHint),
            view.findViewById(R.id.tvOverlayTestReturnHintToggle),
            view.findViewById(R.id.tvOverlayTestReturnHint),
            settings,
            "overlay_test_return",
            scrollHost,
        )
    }

    private fun setupPauseHints(view: View, scrollHost: ScrollView) {
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerPauseOverHint),
            view.findViewById(R.id.tvPauseOverHintToggle),
            view.findViewById(R.id.tvPauseOverHint),
            settings,
            "pause_over",
            scrollHost,
        )
    }

    private fun setupLogHints(view: View, scrollHost: ScrollView) {
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerDebugLogHint),
            view.findViewById(R.id.tvDebugLogHintToggle),
            view.findViewById(R.id.tvDebugLogHint),
            settings,
            "debug_log",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerFileLogHint),
            view.findViewById(R.id.tvFileLogHintToggle),
            view.findViewById(R.id.tvFileLogHint),
            settings,
            "file_log",
            scrollHost,
        )
    }

    private fun setupConfigFooterNote(view: View, scrollHost: ScrollView) {
        val note = view.findViewById<TextView>(R.id.tvConfigPhaseNote)
        val toggleLabel = view.findViewById<TextView>(R.id.tvConfigPhaseNoteToggle)
        val header = view.findViewById<View>(R.id.headerConfigPhaseNote)
        header.preventCollapsibleHeaderFocusSteal()

        fun applyVisible(visible: Boolean) {
            note.visibility = if (visible) View.VISIBLE else View.GONE
            toggleLabel.text = getString(
                if (visible) R.string.config_phase_note_hide else R.string.config_phase_note_show,
            )
        }

        applyVisible(settings.configPhaseNoteVisible)
        header.setOnClickListener {
            scrollHost.runRetainingScrollAndFocus {
                val visible = note.visibility != View.VISIBLE
                settings.configPhaseNoteVisible = visible
                applyVisible(visible)
            }
        }
    }

    private fun setupAutomationHints(view: View, scrollHost: ScrollView) {
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerReturn2Hint),
            view.findViewById(R.id.tvReturn2HintToggle),
            view.findViewById(R.id.tvReturn2Hint),
            settings,
            "return2",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerReturnTriggersHint),
            view.findViewById(R.id.tvReturnTriggersHintToggle),
            view.findViewById(R.id.tvReturnTriggersHint),
            settings,
            "return_triggers",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerAutoAcceptHint),
            view.findViewById(R.id.tvAutoAcceptHintToggle),
            view.findViewById(R.id.tvAutoAcceptHint),
            settings,
            "auto_accept",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerCallOnBlockHint),
            view.findViewById(R.id.tvCallOnBlockHintToggle),
            view.findViewById(R.id.tvCallOnBlockHint),
            settings,
            "call_on_block",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerForegroundHint),
            view.findViewById(R.id.tvForegroundHintToggle),
            view.findViewById(R.id.tvForegroundHint),
            settings,
            "foreground",
            scrollHost,
        )
    }

    private fun setupBotClickHints(view: View, scrollHost: ScrollView) {
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerClickRefreshHint),
            view.findViewById(R.id.tvClickRefreshHintToggle),
            view.findViewById(R.id.tvClickRefreshHint),
            settings,
            "click_refresh",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerBurstClickHint),
            view.findViewById(R.id.tvBurstClickHintToggle),
            view.findViewById(R.id.tvBurstClickHint),
            settings,
            "burst_click",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerAutoScrollHint),
            view.findViewById(R.id.tvAutoScrollHintToggle),
            view.findViewById(R.id.tvAutoScrollHint),
            settings,
            "auto_scroll",
            scrollHost,
        )
    }

    private fun setupClickScreenHints(view: View, scrollHost: ScrollView) {
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerOfferPickHint),
            view.findViewById(R.id.tvOfferPickHintToggle),
            view.findViewById(R.id.tvOfferPickHint),
            settings,
            "offer_pick",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerRefreshButtonHint),
            view.findViewById(R.id.tvRefreshButtonHintToggle),
            view.findViewById(R.id.tvRefreshButtonHint),
            settings,
            "refresh_button",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerRefreshMatchHint),
            view.findViewById(R.id.tvRefreshMatchHintToggle),
            view.findViewById(R.id.tvRefreshMatchHint),
            settings,
            "refresh_match",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerClickScreenHint),
            view.findViewById(R.id.tvClickScreenHintToggle),
            view.findViewById(R.id.tvClickScreenHint),
            settings,
            "click_screen",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerScreenMatchHint),
            view.findViewById(R.id.tvScreenMatchHintToggle),
            view.findViewById(R.id.tvScreenMatchHint),
            settings,
            "screen_match",
            scrollHost,
        )
        ConfigCollapsibleHint.bind(
            view.findViewById(R.id.headerBotClicksHelperHint),
            view.findViewById(R.id.tvBotClicksHelperHintToggle),
            view.findViewById(R.id.tvBotClicksHelperHint),
            settings,
            "bot_clicks_helper",
            scrollHost,
        )
    }

    private fun updateClickModeVisibility(basic: View, smart: View, smartMode: Boolean) {
        basic.visibility = if (smartMode) View.GONE else View.VISIBLE
        smart.visibility = if (smartMode) View.VISIBLE else View.GONE
    }

    private fun updateBurstVisibility(layout: View, enabled: Boolean) {
        layout.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun updateCallOnBlockVisibility(view: View, enabled: Boolean) {
        view.findViewById<View>(R.id.layoutCallOnBlockOptions).visibility =
            if (enabled) View.VISIBLE else View.GONE
    }

    private fun bindCallOnBlockFields(view: View) {
        view.findViewById<SwitchMaterial>(R.id.switchCallOnBlock).isChecked = settings.callOnBlockEnabled
        view.findViewById<TextInputEditText>(R.id.etCallOnBlockPhone)
            .setText(settings.callOnBlockPhoneNumber)
        view.findViewById<SwitchMaterial>(R.id.switchCallOnBlockWhenAccepted).isChecked =
            settings.callOnBlockWhenAccepted
        view.findViewById<SwitchMaterial>(R.id.switchCallOnBlockOnScheduled).isChecked =
            settings.callOnBlockOnScheduledNotification
        view.findViewById<TextInputEditText>(R.id.etCallOnBlockDelayMs)
            .setText(settings.callOnBlockDelayMs.toString())
        updateCallOnBlockVisibility(view, settings.callOnBlockEnabled)
    }

    private fun setupCallOnBlockControls(view: View, markDirty: () -> Unit) {
        val swCallOnBlock = view.findViewById<SwitchMaterial>(R.id.switchCallOnBlock)
        val etCallPhone = view.findViewById<TextInputEditText>(R.id.etCallOnBlockPhone)
        val swWhenAccepted = view.findViewById<SwitchMaterial>(R.id.switchCallOnBlockWhenAccepted)
        val swOnScheduled = view.findViewById<SwitchMaterial>(R.id.switchCallOnBlockOnScheduled)
        val etCallDelay = view.findViewById<TextInputEditText>(R.id.etCallOnBlockDelayMs)
        swCallOnBlock.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (checked && !CallOnBlockHelper.hasCallPermission(requireContext())) {
                swCallOnBlock.isChecked = false
                callPhonePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                return@setOnCheckedChangeRetainingFocus
            }
            settings.callOnBlockEnabled = checked
            updateCallOnBlockVisibility(view, checked)
            markDirty()
        }
        etCallPhone.onUserTextChanged(onDirty = markDirty)
        swWhenAccepted.setOnCheckedChangeRetainingFocus(view) { checked ->
            settings.callOnBlockWhenAccepted = checked
            markDirty()
        }
        swOnScheduled.setOnCheckedChangeRetainingFocus(view) { checked ->
            settings.callOnBlockOnScheduledNotification = checked
            markDirty()
        }
        etCallDelay.onUserTextChanged(onDirty = markDirty)
    }

    private fun persistCallOnBlockFromView(view: View) {
        settings.callOnBlockEnabled = view.findViewById<SwitchMaterial>(R.id.switchCallOnBlock).isChecked
        settings.callOnBlockPhoneNumber =
            view.findViewById<TextInputEditText>(R.id.etCallOnBlockPhone).text?.toString()?.trim().orEmpty()
        settings.callOnBlockWhenAccepted =
            view.findViewById<SwitchMaterial>(R.id.switchCallOnBlockWhenAccepted).isChecked
        settings.callOnBlockOnScheduledNotification =
            view.findViewById<SwitchMaterial>(R.id.switchCallOnBlockOnScheduled).isChecked
        settings.callOnBlockDelayMs =
            view.findViewById<TextInputEditText>(R.id.etCallOnBlockDelayMs).text
                ?.toString()?.toLongOrNull()?.coerceIn(0L, 10_000L) ?: 0L
    }

    private fun persistReturnTimingSettings(
        etReturnStepMin: TextInputEditText,
        etReturnStepMax: TextInputEditText,
        etReturnCooldown: TextInputEditText,
    ) {
        if (suppressAutoPersist) return
        val minStep = etReturnStepMin.text?.toString()?.toIntOrNull()?.coerceIn(0, 60) ?: 1
        val maxStep = etReturnStepMax.text?.toString()?.toIntOrNull()?.coerceIn(0, 60) ?: 3
        settings.flexReturnStepMinSec = minOf(minStep, maxStep)
        settings.flexReturnStepMaxSec = maxOf(minStep, maxStep)
        settings.flexReturnDetectCooldownSec =
            etReturnCooldown.text?.toString()?.toIntOrNull()?.coerceIn(1, 120) ?: 3
        PichixAccessibilityService.syncEngine(requireContext())
    }

    /** Clics / intervalo: se guardan al cambiar para que el motor use Smart click sin pulsar «Guardar». */
    private fun updateReanalyzeAfterRefreshVisibility(
        layout: View,
        hint: View,
        refreshEnabled: Boolean,
    ) {
        val vis = if (refreshEnabled) View.VISIBLE else View.GONE
        layout.visibility = vis
        hint.visibility = vis
    }

    private fun persistClickMotorSettings(
        toggleClickMode: MaterialButtonToggleGroup,
        etBasicSec: TextInputEditText,
        etSmartMin: TextInputEditText,
        etSmartMax: TextInputEditText,
        swClickRefresh: SwitchMaterial,
        swReanalyzeAfterRefresh: SwitchMaterial,
        swBurstClick: SwitchMaterial,
        etBurstMin: TextInputEditText,
        etBurstMax: TextInputEditText,
        etBurstClickMs: TextInputEditText,
        etBurstDurationMin: TextInputEditText,
        etBurstDurationMax: TextInputEditText,
        etRefreshBtn: TextInputEditText,
        etClickScreen: TextInputEditText,
        toggleRefreshMode: MaterialButtonToggleGroup,
        swRefreshIgnore: SwitchMaterial,
        toggleScreenMode: MaterialButtonToggleGroup,
        swScreenIgnore: SwitchMaterial,
    ) {
        if (suppressAutoPersist) return
        val smartSelected = toggleClickMode.checkedButtonId == R.id.btnClickModeSmart
        settings.flexClickMode = if (smartSelected) AppSettings.CLICK_MODE_SMART else AppSettings.CLICK_MODE_BASIC
        val sec = etBasicSec.text?.toString()?.toIntOrNull()?.coerceIn(1, 60) ?: 3
        settings.flexGrabIntervalMs = sec * 1000L
        val minSec = etSmartMin.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 1
        val maxSec = etSmartMax.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 6
        settings.flexSmartClickMinSec = minOf(minSec, maxSec)
        settings.flexSmartClickMaxSec = maxOf(minSec, maxSec)
        settings.flexClickRefreshEnabled = swClickRefresh.isChecked
        settings.flexReanalyzeAfterRefreshEnabled = swReanalyzeAfterRefresh.isChecked
        settings.flexBurstClickEnabled = swBurstClick.isChecked
        val burstMin = etBurstMin.text?.toString()?.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 5
        val burstMax = etBurstMax.text?.toString()?.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 15
        settings.flexBurstIntervalMinMin = minOf(burstMin, burstMax)
        settings.flexBurstIntervalMaxMin = maxOf(burstMin, burstMax)
        settings.flexBurstClickIntervalMs =
            etBurstClickMs.text?.toString()?.toLongOrNull()?.coerceIn(100L, 10_000L) ?: 500L
        val burstDurMin = etBurstDurationMin.text?.toString()?.toIntOrNull()?.coerceIn(5, 3600) ?: 20
        val burstDurMax = etBurstDurationMax.text?.toString()?.toIntOrNull()?.coerceIn(5, 3600) ?: 40
        settings.flexBurstDurationMinSec = minOf(burstDurMin, burstDurMax)
        settings.flexBurstDurationMaxSec = maxOf(burstDurMin, burstDurMax)
        settings.flexRefreshButtonText =
            etRefreshBtn.text?.toString()?.trim().orEmpty().ifBlank { AppSettings.DEFAULT_REFRESH_BUTTON }
        settings.flexRefreshButtonMatchMode =
            TextMatchUiHelper.readMatchMode(toggleRefreshMode, R.id.btnRefreshMatchExact)
        settings.flexRefreshButtonIgnoreCase = swRefreshIgnore.isChecked
        settings.flexClickScreenText = etClickScreen.text?.toString()?.trim().orEmpty()
        settings.flexClickScreenMatchMode =
            TextMatchUiHelper.readMatchMode(toggleScreenMode, R.id.btnScreenMatchExact)
        settings.flexClickScreenIgnoreCase = swScreenIgnore.isChecked
        PichixAccessibilityService.syncEngine(requireContext())
    }

    /** Escribe todos los campos del formulario en [AppSettings] (equivalente a pulsar Guardar). */
    fun saveAllFieldsToSettings(): Boolean {
        val view = configRootView ?: return false
        val autoAcceptSwitch = swAutoAccept ?: return false
        val previousSuppress = suppressAutoPersist
        suppressAutoPersist = false
        try {
            persistAllFields(
                view.findViewById(R.id.etFlexPackage),
                view.findViewById(R.id.switchShowCategoryNames),
                autoAcceptSwitch,
                view.findViewById(R.id.switchPauseOverClicks),
                view.findViewById(R.id.switchFlexOnlyForeground),
                view.findViewById(R.id.switchDebugLog),
                view.findViewById(R.id.switchFileLog),
                view.findViewById(R.id.switchOverlayOnOff),
                view.findViewById(R.id.switchOverlayMotorPause),
                view.findViewById(R.id.switchOverlayTestReturn),
                view.findViewById(R.id.etReturnStepMinSec),
                view.findViewById(R.id.etReturnStepMaxSec),
                view.findViewById(R.id.etReturnDetectCooldownSec),
                view.findViewById(R.id.etPauseOverClicksText),
                view.findViewById(R.id.etPauseOverClicksMinutes),
                view.findViewById(R.id.toggleClickMode),
                view.findViewById(R.id.etBasicClickIntervalSec),
                view.findViewById(R.id.etSmartClickMinSec),
                view.findViewById(R.id.etSmartClickMaxSec),
                view.findViewById(R.id.switchClickRefresh),
                view.findViewById(R.id.switchBurstClick),
                view.findViewById(R.id.etBurstIntervalMinMin),
                view.findViewById(R.id.etBurstIntervalMaxMin),
                view.findViewById(R.id.etBurstClickIntervalMs),
                view.findViewById(R.id.etBurstDurationMinSec),
                view.findViewById(R.id.etBurstDurationMaxSec),
                view.findViewById(R.id.switchAutoScroll),
                view.findViewById(R.id.toggleOfferPickMode),
                view.findViewById(R.id.toggleOfferRank),
                view.findViewById(R.id.etRefreshButtonText),
                view.findViewById(R.id.etClickScreenText),
                view.findViewById(R.id.toggleRefreshButtonMatchMode),
                view.findViewById(R.id.switchRefreshButtonIgnoreCase),
                view.findViewById(R.id.toggleScreenMatchMode),
                view.findViewById(R.id.switchScreenIgnoreCase),
                view.findViewById(R.id.togglePauseMatchMode),
                view.findViewById(R.id.switchPauseIgnoreCase),
            )
        } finally {
            suppressAutoPersist = previousSuppress
        }
        return true
    }

    private fun persistAllFields(
        etPackage: TextInputEditText,
        swShowNames: SwitchMaterial,
        swAutoAccept: SwitchMaterial,
        swPauseOver: SwitchMaterial,
        swForeground: SwitchMaterial,
        swDebug: SwitchMaterial,
        swFileLog: SwitchMaterial,
        swOverlayOnOff: SwitchMaterial,
        swOverlayMotorPause: SwitchMaterial,
        swOverlayTestReturn: SwitchMaterial,
        etReturnStepMin: TextInputEditText,
        etReturnStepMax: TextInputEditText,
        etReturnCooldown: TextInputEditText,
        etPauseText: TextInputEditText,
        etPauseMinutes: TextInputEditText,
        toggleClickMode: MaterialButtonToggleGroup,
        etBasicSec: TextInputEditText,
        etSmartMin: TextInputEditText,
        etSmartMax: TextInputEditText,
        swClickRefresh: SwitchMaterial,
        swBurstClick: SwitchMaterial,
        etBurstMin: TextInputEditText,
        etBurstMax: TextInputEditText,
        etBurstClickMs: TextInputEditText,
        etBurstDurationMin: TextInputEditText,
        etBurstDurationMax: TextInputEditText,
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
        val minStep = etReturnStepMin.text?.toString()?.toIntOrNull()?.coerceIn(0, 60) ?: 1
        val maxStep = etReturnStepMax.text?.toString()?.toIntOrNull()?.coerceIn(0, 60) ?: 3
        settings.flexReturnStepMinSec = minOf(minStep, maxStep)
        settings.flexReturnStepMaxSec = maxOf(minStep, maxStep)
        settings.flexReturnDetectCooldownSec =
            etReturnCooldown.text?.toString()?.toIntOrNull()?.coerceIn(1, 120) ?: 3
        settings.flexAutoAccept = swAutoAccept.isChecked
        configRootView?.findViewById<SwitchMaterial>(R.id.switchContinueOnTakeMiss)?.let {
            settings.flexContinueOnTakeMiss = it.isChecked
        }
        configRootView?.findViewById<SwitchMaterial>(R.id.switchPauseAfterAccept)?.let {
            settings.autoPauseAfterAccept = it.isChecked
        }
        configRootView?.let { persistCallOnBlockFromView(it) }
        settings.pauseByOverClicksEnabled = swPauseOver.isChecked
        settings.flexOnlyWhenForeground = swForeground.isChecked
        settings.overlayEnabled = swOverlayOnOff.isChecked
        settings.overlayMotorPauseFabEnabled = swOverlayMotorPause.isChecked
        settings.overlayTestReturnEnabled = swOverlayTestReturn.isChecked
        OverlayService.sync(requireContext())
        settings.debugLogEnabled = swDebug.isChecked
        settings.fileLogEnabled = swFileLog.isChecked
        PichiFileLog.setFileLogEnabled(settings.fileLogEnabled)
        settings.pauseByOverClicksMatchText = etPauseText.text?.toString()?.trim().orEmpty()
        settings.pauseByOverClicksResumeMinutes =
            etPauseMinutes.text?.toString()?.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 5
        settings.pauseByOverClicksPauseSoundUri = pauseSoundUri
        settings.pauseByOverClicksResumeSoundUri = resumeSoundUri
        settings.offerClickSoundUri = offerClickSoundUri
        settings.flexDetailMismatchSoundUri = mismatchSoundUri
        configRootView?.let { root ->
            settings.offerClickSoundEnabled =
                root.findViewById<SwitchMaterial>(R.id.switchOfferClickSound).isChecked
            settings.offerClickSoundRepeatCount = readOfferClickSoundRepeat(root)
            val mismatchToggle = root.findViewById<MaterialButtonToggleGroup>(R.id.toggleDetailMismatchAction)
            settings.flexDetailMismatchAction =
                if (mismatchToggle.checkedButtonId == R.id.btnMismatchAutoCancel) {
                    AppSettings.MISMATCH_ACTION_AUTO_CANCEL
                } else {
                    AppSettings.MISMATCH_ACTION_SOUND_STAY
                }
        }
        val smartSelected = toggleClickMode.checkedButtonId == R.id.btnClickModeSmart
        settings.flexClickMode = if (smartSelected) AppSettings.CLICK_MODE_SMART else AppSettings.CLICK_MODE_BASIC
        val sec = etBasicSec.text?.toString()?.toIntOrNull()?.coerceIn(1, 60) ?: 3
        settings.flexGrabIntervalMs = sec * 1000L
        val minSec = etSmartMin.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 1
        val maxSec = etSmartMax.text?.toString()?.toIntOrNull()?.coerceIn(1, 3600) ?: 6
        settings.flexSmartClickMinSec = minOf(minSec, maxSec)
        settings.flexSmartClickMaxSec = maxOf(minSec, maxSec)
        settings.flexClickRefreshEnabled = swClickRefresh.isChecked
        settings.flexReanalyzeAfterRefreshEnabled =
            configRootView?.findViewById<SwitchMaterial>(R.id.switchReanalyzeAfterRefresh)?.isChecked
                ?: settings.flexReanalyzeAfterRefreshEnabled
        settings.flexBurstClickEnabled = swBurstClick.isChecked
        val burstMin = etBurstMin.text?.toString()?.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 5
        val burstMax = etBurstMax.text?.toString()?.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 15
        settings.flexBurstIntervalMinMin = minOf(burstMin, burstMax)
        settings.flexBurstIntervalMaxMin = maxOf(burstMin, burstMax)
        settings.flexBurstClickIntervalMs =
            etBurstClickMs.text?.toString()?.toLongOrNull()?.coerceIn(100L, 10_000L) ?: 500L
        val burstDurMin = etBurstDurationMin.text?.toString()?.toIntOrNull()?.coerceIn(5, 3600) ?: 20
        val burstDurMax = etBurstDurationMax.text?.toString()?.toIntOrNull()?.coerceIn(5, 3600) ?: 40
        settings.flexBurstDurationMinSec = minOf(burstDurMin, burstDurMax)
        settings.flexBurstDurationMaxSec = maxOf(burstDurMin, burstDurMax)
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
        MainActivity.notifyAutoAcceptSettingChanged(requireContext(), settings.flexAutoAccept)
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

    private fun readOfferClickSoundRepeat(view: View): Int =
        view.findViewById<TextInputEditText>(R.id.etOfferClickSoundRepeat).text
            ?.toString()?.toIntOrNull()?.coerceIn(1, 20) ?: 1

    private fun setupOfferClickSoundControls(view: View, markDirty: () -> Unit) {
        view.findViewById<TextInputEditText>(R.id.etOfferClickSoundRepeat)
            .setText(settings.offerClickSoundRepeatCount.toString())
        view.findViewById<SwitchMaterial>(R.id.switchOfferClickSound)?.apply {
            isChecked = settings.offerClickSoundEnabled
            setOnCheckedChangeRetainingFocus(view) { checked ->
                settings.offerClickSoundEnabled = checked
                markDirty()
            }
        }
        view.findViewById<MaterialButton>(R.id.btnPickOfferClickSound)?.setOnClickListener {
            offerClickSoundPicker.pickRingtone(offerClickSoundUri)
        }
        view.findViewById<MaterialButton>(R.id.btnPickOfferClickAudioFile)?.setOnClickListener {
            offerClickSoundPicker.pickAudioFromDevice()
        }
        view.findViewById<MaterialButton>(R.id.btnOfferClickSoundSystem)?.setOnClickListener {
            offerClickSoundUri = ""
            refreshOfferClickSoundLabel(view)
            markDirty()
        }
        view.findViewById<MaterialButton>(R.id.btnOfferClickSoundPlay)?.setOnClickListener {
            AlertManager(requireContext()).playFlexNotificationAlert(
                offerClickSoundUri,
                readOfferClickSoundRepeat(view),
            )
        }
        view.findViewById<MaterialButton>(R.id.btnOfferClickSoundStop)?.setOnClickListener {
            AlertManager.stopGlobal()
        }
        view.findViewById<TextInputEditText>(R.id.etOfferClickSoundRepeat)?.onUserTextChanged(onDirty = markDirty)
        refreshOfferClickSoundLabel(view)
    }

    private fun setupDetailMismatchControls(view: View, markDirty: () -> Unit) {
        val toggle = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleDetailMismatchAction)
        val layoutSound = view.findViewById<View>(R.id.layoutMismatchSound)
        fun syncMismatchUi() {
            val autoCancel = toggle.checkedButtonId == R.id.btnMismatchAutoCancel
            layoutSound.visibility = if (autoCancel) View.GONE else View.VISIBLE
        }
        if (settings.usesDetailMismatchAutoCancel()) {
            toggle.safeCheck(R.id.btnMismatchAutoCancel)
        } else {
            toggle.safeCheck(R.id.btnMismatchSoundStay)
        }
        syncMismatchUi()
        SegmentedToggleStyle.wireGroup(toggle, configScroll) { checkedId ->
            if (suppressUiEvents) return@wireGroup
            settings.flexDetailMismatchAction = if (checkedId == R.id.btnMismatchAutoCancel) {
                AppSettings.MISMATCH_ACTION_AUTO_CANCEL
            } else {
                AppSettings.MISMATCH_ACTION_SOUND_STAY
            }
            syncMismatchUi()
            markDirty()
        }
        view.findViewById<MaterialButton>(R.id.btnPickMismatchSound)?.setOnClickListener {
            mismatchSoundPicker.pickRingtone(mismatchSoundUri)
        }
        view.findViewById<MaterialButton>(R.id.btnPickMismatchAudioFile)?.setOnClickListener {
            mismatchSoundPicker.pickAudioFromDevice()
        }
    }

    private fun refreshMismatchSoundLabel(view: View) {
        val ctx = requireContext()
        view.findViewById<TextView>(R.id.tvMismatchSoundLabel)?.text =
            getString(R.string.config_detail_mismatch_sound_label) + ": " +
                SoundUriLabel.label(ctx, mismatchSoundUri)
    }

    private fun refreshOfferClickSoundLabel(view: View) {
        val ctx = requireContext()
        view.findViewById<TextView>(R.id.tvOfferClickSoundLabel)?.text =
            getString(R.string.config_offer_click_sound_label) + ": " +
                SoundUriLabel.label(ctx, offerClickSoundUri)
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
        group.safeCheck(
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

    fun refreshAccessibilityStatus() = refreshPermissionStatuses()

    private fun refreshPermissionStatuses() {
        if (!isAdded) return
        val ctx = context ?: return
        val activity = activity as? MainActivity
        val accessEnabled = activity?.isAccessibilityEnabled()
            ?: PermissionStatusHelper.isAccessibilityServiceEnabled(ctx)
        applyPermissionStatus(tvAccess, accessEnabled)

        val notificationEnabled = PermissionStatusHelper.isNotificationListenerEnabled(ctx)
        applyPermissionStatus(tvNotificationAccess, notificationEnabled)

        val overlayEnabled = OverlayPermissionHelper.canDrawOverlays(ctx)
        applyPermissionStatus(tvOverlayPermission, overlayEnabled)

        updatePermissionBanner(accessEnabled, notificationEnabled, overlayEnabled)
    }

    private fun updatePermissionBanner(
        accessEnabled: Boolean,
        notificationEnabled: Boolean,
        overlayEnabled: Boolean,
    ) {
        val banner = tvPermissionBanner ?: return
        val ctx = requireContext()
        val missing = listOf(!accessEnabled, !notificationEnabled, !overlayEnabled).count { it }
        if (missing == 0) {
            banner.text = getString(R.string.config_perm_banner_ok)
            banner.setTextColor(ContextCompat.getColor(ctx, R.color.green_400))
        } else {
            banner.text = getString(R.string.config_perm_banner_missing, missing)
            banner.setTextColor(ContextCompat.getColor(ctx, R.color.coral_600))
        }
    }

    private fun refreshSaveFooter() {
        val activity = activity as? MainActivity ?: return
        tvSaveDirty?.visibility =
            if (activity.isTabDirty(1)) View.VISIBLE else View.GONE
    }

    private fun applyPermissionStatus(label: TextView?, enabled: Boolean) {
        val ctx = requireContext()
        label?.text = if (enabled) "✓ Activado" else "✗ No activado"
        label?.setTextColor(
            ContextCompat.getColor(ctx, if (enabled) R.color.green_400 else R.color.coral_600),
        )
    }

    private fun reloadReturnTimingFromSettings() {
        val view = configRootView ?: return
        if (!isAdded) return
        val ctx = context ?: return
        settings = AppSettings(ctx)
        suppressAutoPersist = true
        suppressUiEvents = true
        try {
            val apply: () -> Unit = {
            view.findViewById<TextInputEditText>(R.id.etReturnStepMinSec)
                .setText(settings.flexReturnStepMinSec.toString())
            view.findViewById<TextInputEditText>(R.id.etReturnStepMaxSec)
                .setText(settings.flexReturnStepMaxSec.toString())
            view.findViewById<TextInputEditText>(R.id.etReturnDetectCooldownSec)
                .setText(settings.flexReturnDetectCooldownSec.toString())
            suppressReturn2Sync = true
            try {
                swReturn2?.isChecked = settings.flexAutoReturnToOffers
            } finally {
                suppressReturn2Sync = false
            }
            suppressAutoAcceptSync = true
            try {
                swAutoAccept?.isChecked = settings.flexAutoAccept
            } finally {
                suppressAutoAcceptSync = false
            }
            view.findViewById<SwitchMaterial>(R.id.switchContinueOnTakeMiss).isChecked =
                settings.flexContinueOnTakeMiss
            view.findViewById<SwitchMaterial>(R.id.switchPauseAfterAccept).isChecked =
                settings.autoPauseAfterAccept
        }
        if (::configScroll.isInitialized) {
            configScroll.runRetainingScrollAndFocus { apply() }
        } else {
            apply()
        }
        } finally {
            suppressAutoPersist = false
            suppressUiEvents = false
        }
    }

    /** Recarga todo el formulario desde disco (importación o primera apertura tras restaurar estado). */
    fun reloadConfigFieldsFromSettings() {
        val view = configRootView ?: return
        if (!isAdded) return
        val ctx = context ?: return
        settings = AppSettings(ctx)
        suppressAutoPersist = true
        suppressUiEvents = true
        try {
        val reload: () -> Unit = {
            try {
            view.findViewById<TextInputEditText>(R.id.etFlexPackage)
                .setText(settings.monitorPackagesCsv)
            view.findViewById<SwitchMaterial>(R.id.switchShowCategoryNames).isChecked =
                settings.showCategoryNames
            suppressReturn2Sync = true
            try {
                swReturn2?.isChecked = settings.flexAutoReturnToOffers
            } finally {
                suppressReturn2Sync = false
            }
            view.findViewById<TextInputEditText>(R.id.etReturnStepMinSec)
                .setText(settings.flexReturnStepMinSec.toString())
            view.findViewById<TextInputEditText>(R.id.etReturnStepMaxSec)
                .setText(settings.flexReturnStepMaxSec.toString())
            view.findViewById<TextInputEditText>(R.id.etReturnDetectCooldownSec)
                .setText(settings.flexReturnDetectCooldownSec.toString())
            suppressAutoAcceptSync = true
            try {
                swAutoAccept?.isChecked = settings.flexAutoAccept
            } finally {
                suppressAutoAcceptSync = false
            }
            view.findViewById<SwitchMaterial>(R.id.switchContinueOnTakeMiss).isChecked =
                settings.flexContinueOnTakeMiss
            view.findViewById<SwitchMaterial>(R.id.switchPauseAfterAccept).isChecked =
                settings.autoPauseAfterAccept
            view.findViewById<SwitchMaterial>(R.id.switchPauseOverClicks).isChecked =
                settings.pauseByOverClicksEnabled
            view.findViewById<SwitchMaterial>(R.id.switchFlexOnlyForeground).isChecked =
                settings.flexOnlyWhenForeground
            view.findViewById<SwitchMaterial>(R.id.switchOverlayOnOff).isChecked =
                settings.overlayEnabled
            view.findViewById<SwitchMaterial>(R.id.switchOverlayMotorPause).isChecked =
                settings.overlayMotorPauseFabEnabled
            view.findViewById<SwitchMaterial>(R.id.switchOverlayTestReturn).isChecked =
                settings.overlayTestReturnEnabled
            view.findViewById<SwitchMaterial>(R.id.switchDebugLog).isChecked =
                settings.debugLogEnabled
            view.findViewById<SwitchMaterial>(R.id.switchFileLog).isChecked =
                settings.fileLogEnabled
            view.findViewById<TextInputEditText>(R.id.etPauseOverClicksText)
                .setText(settings.pauseByOverClicksMatchText)
            view.findViewById<TextInputEditText>(R.id.etPauseOverClicksMinutes)
                .setText(settings.pauseByOverClicksResumeMinutes.toString())
            pauseSoundUri = settings.pauseByOverClicksPauseSoundUri
            resumeSoundUri = settings.pauseByOverClicksResumeSoundUri
            offerClickSoundUri = settings.offerClickSoundUri
            mismatchSoundUri = settings.flexDetailMismatchSoundUri
            view.findViewById<SwitchMaterial>(R.id.switchOfferClickSound).isChecked =
                settings.offerClickSoundEnabled
            view.findViewById<TextInputEditText>(R.id.etOfferClickSoundRepeat)
                .setText(settings.offerClickSoundRepeatCount.toString())
            if (settings.usesDetailMismatchAutoCancel()) {
                view.findViewById<MaterialButtonToggleGroup>(R.id.toggleDetailMismatchAction)
                    .safeCheck(R.id.btnMismatchAutoCancel)
            } else {
                view.findViewById<MaterialButtonToggleGroup>(R.id.toggleDetailMismatchAction)
                    .safeCheck(R.id.btnMismatchSoundStay)
            }
            view.findViewById<View>(R.id.layoutMismatchSound).visibility =
                if (settings.usesDetailMismatchAutoCancel()) View.GONE else View.VISIBLE
            val basicSec = (settings.flexGrabIntervalMs / 1000L).toInt().coerceIn(1, 60)
            view.findViewById<TextInputEditText>(R.id.etBasicClickIntervalSec).setText(basicSec.toString())
            view.findViewById<TextInputEditText>(R.id.etSmartClickMinSec)
                .setText(settings.flexSmartClickMinSec.toString())
            view.findViewById<TextInputEditText>(R.id.etSmartClickMaxSec)
                .setText(settings.flexSmartClickMaxSec.toString())
            val swBurstClick = view.findViewById<SwitchMaterial>(R.id.switchBurstClick)
            swBurstClick.isChecked = settings.flexBurstClickEnabled
            view.findViewById<TextInputEditText>(R.id.etBurstIntervalMinMin)
                .setText(settings.flexBurstIntervalMinMin.toString())
            view.findViewById<TextInputEditText>(R.id.etBurstIntervalMaxMin)
                .setText(settings.flexBurstIntervalMaxMin.toString())
            view.findViewById<TextInputEditText>(R.id.etBurstClickIntervalMs)
                .setText(settings.flexBurstClickIntervalMs.toString())
            view.findViewById<TextInputEditText>(R.id.etBurstDurationMinSec)
                .setText(settings.flexBurstDurationMinSec.toString())
            view.findViewById<TextInputEditText>(R.id.etBurstDurationMaxSec)
                .setText(settings.flexBurstDurationMaxSec.toString())
            updateBurstVisibility(view.findViewById(R.id.layoutBurstClick), swBurstClick.isChecked)
            view.findViewById<SwitchMaterial>(R.id.switchAutoScroll).isChecked =
                settings.flexAutoScrollEnabled
            val toggleOfferPick = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleOfferPickMode)
            toggleOfferPick.safeCheck(
                if (settings.usesBestOfferPick()) R.id.btnOfferPickBest else R.id.btnOfferPickFirst,
            )
            val toggleOfferRank = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleOfferRank)
            setOfferRankToggle(toggleOfferRank, settings.flexOfferRankCriterion)
            view.findViewById<LinearLayout>(R.id.layoutOfferRank).visibility =
                if (toggleOfferPick.checkedButtonId == R.id.btnOfferPickBest) View.VISIBLE else View.GONE
            view.findViewById<TextInputEditText>(R.id.etRefreshButtonText)
                .setText(settings.flexRefreshButtonText)
            view.findViewById<TextInputEditText>(R.id.etClickScreenText)
                .setText(settings.flexClickScreenText)
            val smartMode = settings.flexClickMode == AppSettings.CLICK_MODE_SMART
            view.findViewById<MaterialButtonToggleGroup>(R.id.toggleClickMode).safeCheck(
                if (smartMode) R.id.btnClickModeSmart else R.id.btnClickModeBasic,
            )
            updateClickModeVisibility(
                view.findViewById(R.id.layoutBasicClick),
                view.findViewById(R.id.layoutSmartClick),
                smartMode,
            )
            val swClickRefreshReload = view.findViewById<SwitchMaterial>(R.id.switchClickRefresh)
            swClickRefreshReload.isChecked = settings.flexClickRefreshEnabled
            view.findViewById<SwitchMaterial>(R.id.switchReanalyzeAfterRefresh).isChecked =
                settings.flexReanalyzeAfterRefreshEnabled
            updateReanalyzeAfterRefreshVisibility(
                view.findViewById(R.id.layoutReanalyzeAfterRefresh),
                view.findViewById(R.id.tvReanalyzeAfterRefreshHint),
                swClickRefreshReload.isChecked,
            )
            TextMatchUiHelper.setMatchMode(
                view.findViewById(R.id.toggleRefreshButtonMatchMode),
                settings.flexRefreshButtonMatchMode,
                R.id.btnRefreshMatchContains,
                R.id.btnRefreshMatchExact,
            )
            view.findViewById<SwitchMaterial>(R.id.switchRefreshButtonIgnoreCase).isChecked =
                settings.flexRefreshButtonIgnoreCase
            TextMatchUiHelper.setMatchMode(
                view.findViewById(R.id.toggleScreenMatchMode),
                settings.flexClickScreenMatchMode,
                R.id.btnScreenMatchContains,
                R.id.btnScreenMatchExact,
            )
            view.findViewById<SwitchMaterial>(R.id.switchScreenIgnoreCase).isChecked =
                settings.flexClickScreenIgnoreCase
            TextMatchUiHelper.setMatchMode(
                view.findViewById(R.id.togglePauseMatchMode),
                settings.pauseByOverClicksMatchMode,
                R.id.btnPauseMatchContains,
                R.id.btnPauseMatchExact,
            )
            view.findViewById<SwitchMaterial>(R.id.switchPauseIgnoreCase).isChecked =
                settings.pauseByOverClicksIgnoreCase
            bindCallOnBlockFields(view)
            returnTriggers = FlexReturnTriggersStore.load(settings).toMutableList()
            returnTriggersAdapter?.submit(returnTriggers.toList())
            refreshSoundLabels(view)
            refreshOfferClickSoundLabel(view)
            refreshMismatchSoundLabel(view)
            refreshPermissionStatuses()
            refreshSaveFooter()
            formBoundFromDisk = true
            } catch (e: Exception) {
                PichiFileLog.i("Config", "reload after import failed: ${e.message}", always = true)
                Toast.makeText(ctx, getString(R.string.config_reload_error), Toast.LENGTH_LONG).show()
            }
        }
        if (::configScroll.isInitialized) {
            configScroll.runRetainingScrollAndFocus { reload() }
        } else {
            reload()
        }
        } finally {
            suppressAutoPersist = false
            suppressUiEvents = false
        }
    }

    override fun onResume() {
        super.onResume()
        val mustReload = !formBoundFromDisk || AppSettings.isPendingConfigUiReload()
        if (mustReload) {
            reloadConfigFieldsFromSettings()
            (activity as? MainActivity)?.clearDirty(1)
            if (AppSettings.isPendingConfigUiReload()) {
                AppSettings.clearPendingConfigUiReload()
            }
        } else if (::configScroll.isInitialized) {
            configScroll.runRetainingScrollAndFocus {
                refreshPermissionStatuses()
                reloadReturnTimingFromSettings()
                refreshSaveFooter()
            }
        } else {
            refreshPermissionStatuses()
            reloadReturnTimingFromSettings()
            refreshSaveFooter()
        }
        if (!AppSettings.isPendingConfigUiReload()) {
            suppressAutoPersist = false
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).apply {
            registerReceiver(return2Receiver, IntentFilter(MainActivity.RETURN2_SETTING_CHANGED))
            registerReceiver(autoAcceptReceiver, IntentFilter(MainActivity.AUTO_ACCEPT_SETTING_CHANGED))
            registerReceiver(configImportedReceiver, IntentFilter(MainActivity.CONFIG_IMPORTED))
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            val lbm = LocalBroadcastManager.getInstance(requireContext())
            lbm.unregisterReceiver(return2Receiver)
            lbm.unregisterReceiver(autoAcceptReceiver)
            lbm.unregisterReceiver(configImportedReceiver)
        } catch (_: Exception) {
        }
    }
}
