package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.TypedValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.PichiFileLog
import com.oceanlab.pichix.data.PichixConfigBackup
import com.oceanlab.pichix.service.OverlayService
import com.oceanlab.pichix.service.PichixAccessibilityService
import com.oceanlab.pichix.util.OverlayPermissionHelper

class HomeFragment : Fragment() {

    private lateinit var settings: AppSettings
    private var tvStatus: TextView? = null
    private var tvSubtitle: TextView? = null
    private var statusBar: View? = null
    private var themeToggleGroup: MaterialButtonToggleGroup? = null
    private var swOverlay: SwitchMaterial? = null
    private var swDryRun: SwitchMaterial? = null
    private var swAutoAccept: SwitchMaterial? = null
    private var swReturn2Offers: SwitchMaterial? = null

    private var syncing = false
    private var suppressThemeToggle = false
    private var suppressReturn2Sync = false
    private var suppressAutoAcceptSync = false

    private val exportConfigLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null || !isAdded) return@registerForActivityResult
        try {
            val result = PichixConfigBackup.export(requireContext())
            PichixConfigBackup.writeText(requireContext(), uri, result.json)
            Toast.makeText(
                requireContext(),
                getString(R.string.home_config_export_ok_count, result.keysExported),
                Toast.LENGTH_LONG,
            ).show()
        } catch (e: Exception) {
            showConfigError(e)
        }
    }

    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || !isAdded) return@registerForActivityResult
        confirmImport(uri)
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

    private val return2Receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MainActivity.RETURN2_SETTING_CHANGED) return
            suppressReturn2Sync = true
            try {
                swReturn2Offers?.isChecked =
                    intent.getBooleanExtra(MainActivity.EXTRA_RETURN2_ENABLED, settings.flexAutoReturnToOffers)
            } finally {
                suppressReturn2Sync = false
            }
        }
    }

    private val botStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isAdded) return
            if (intent.action == MainActivity.CONFIG_IMPORTED) {
                settings = AppSettings(context.applicationContext)
            }
            syncSwitches()
            refreshStatus()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext())
        view.setupFormFocus()
        val activity = requireActivity() as MainActivity

        tvStatus = view.findViewById(R.id.homeBotStatus)
        tvSubtitle = view.findViewById(R.id.homeBotSubtitle)
        statusBar = view.findViewById(R.id.homeStatusBar)
        themeToggleGroup = view.findViewById(R.id.themeToggleGroup)
        swOverlay = view.findViewById(R.id.homeQuickOverlay)
        swDryRun = view.findViewById(R.id.homeQuickDryRun)
        swAutoAccept = view.findViewById(R.id.homeQuickAutoAccept)
        swReturn2Offers = view.findViewById(R.id.homeQuickReturn2Offers)

        syncSwitches()
        setupThemeToggle(activity)

        view.findViewById<MaterialButton>(R.id.btnHomeExportConfig).setOnClickListener {
            (activity as MainActivity).flushConfigFormBeforeExport()
            exportConfigLauncher.launch(PichixConfigBackup.suggestedExportFileName())
        }
        view.findViewById<MaterialButton>(R.id.btnHomeImportConfig).setOnClickListener {
            importConfigLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        swOverlay?.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (syncing) return@setOnCheckedChangeRetainingFocus
            if (checked && !OverlayPermissionHelper.canDrawOverlays(requireContext())) {
                OverlayPermissionHelper.openOverlaySettings(requireContext())
                syncing = true
                swOverlay?.isChecked = false
                syncing = false
                Toast.makeText(requireContext(), "Concede «Mostrar sobre otras apps»", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeRetainingFocus
            }
            settings.overlayEnabled = checked
            OverlayService.sync(requireContext())
        }

        swDryRun?.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (syncing) return@setOnCheckedChangeRetainingFocus
            settings.dryRunMode = checked
            refreshStatus()
            activity.updateHeader()
            LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(Intent(MainActivity.BOT_STATE_CHANGED))
        }

        swAutoAccept?.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (syncing || suppressAutoAcceptSync) return@setOnCheckedChangeRetainingFocus
            settings.flexAutoAccept = checked
            MainActivity.notifyAutoAcceptSettingChanged(requireContext(), checked)
        }

        swReturn2Offers?.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (syncing || suppressReturn2Sync) return@setOnCheckedChangeRetainingFocus
            settings.flexAutoReturnToOffers = checked
            MainActivity.notifyReturn2SettingChanged(requireContext(), checked)
            PichixAccessibilityService.syncEngine(requireContext())
        }

        refreshStatus()
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
            .setTitle(R.string.home_config_import_title)
            .setMessage(R.string.home_config_import_message)
            .setPositiveButton(R.string.home_config_import) { _, _ -> performImport(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performImport(uri: Uri) {
        try {
            val previousDark = settings.useDarkTheme
            val json = PichixConfigBackup.readText(requireContext(), uri)
            val result = PichixConfigBackup.importFromJson(requireContext(), json)
            settings = AppSettings(requireContext())
            showImportResultDialog(previousDark, result)
        } catch (e: Exception) {
            showConfigError(e)
        }
    }

    private fun showImportResultDialog(previousDark: Boolean, result: PichixConfigBackup.ImportResult) {
        val report = PichixConfigBackup.formatImportReport(result)
        val scroll = ScrollView(requireContext()).apply {
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val body = TextView(requireContext()).apply {
            text = report
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(body)

        AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
            .setTitle(getString(R.string.home_config_import_result_title, result.keysImported, result.keysSkipped))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyImportedConfig(previousDark)
            }
            .setCancelable(false)
            .show()
    }

    private fun applyImportedConfig(previousDark: Boolean) {
        if (!isAdded) return
        try {
            val ctx = requireContext().applicationContext
            settings = AppSettings(ctx)
            val activity = requireActivity() as MainActivity
            activity.reloadSettingsFromDisk()
            OverlayService.sync(ctx)
            MonitorPackages.notifyReload(ctx)
            PichixAccessibilityService.syncEngine(ctx)
            PichiFileLog.setFileLogEnabled(settings.fileLogEnabled)
            MainActivity.notifyReturn2SettingChanged(ctx, settings.flexAutoReturnToOffers)
            MainActivity.notifyAutoAcceptSettingChanged(ctx, settings.flexAutoAccept)
            if (settings.useDarkTheme != previousDark) {
                ThemeHelper.setDarkTheme(ctx, settings.useDarkTheme)
                view?.post {
                    if (isAdded) activity.recreate()
                }
                return
            }
            activity.notifyConfigImported()
            syncSwitches()
            refreshStatus()
            Toast.makeText(
                requireContext(),
                getString(R.string.home_config_import_applied),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Exception) {
            showConfigError(e)
        }
    }

    private fun showConfigError(e: Exception) {
        Toast.makeText(
            requireContext(),
            getString(R.string.home_config_error, e.message ?: e.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun setupThemeToggle(activity: MainActivity) {
        val group = themeToggleGroup ?: return
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressThemeToggle) return@addOnButtonCheckedListener
            val wantDark = checkedId == R.id.homeBtnThemeDark
            if (wantDark == settings.useDarkTheme) return@addOnButtonCheckedListener
            ThemeHelper.setDarkTheme(requireContext(), wantDark)
            activity.recreate()
        }
        group.post {
            suppressThemeToggle = true
            try {
                group.check(
                    if (settings.useDarkTheme) R.id.homeBtnThemeDark else R.id.homeBtnThemeWhite
                )
            } catch (_: IllegalStateException) {
                // Evita crash si el toggle aún no está totalmente medido.
            } finally {
                suppressThemeToggle = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settings = AppSettings(requireContext())
        syncSwitches()
        val filter = IntentFilter().apply {
            addAction(MainActivity.BOT_STATE_CHANGED)
            addAction(MainActivity.BOT_PAUSED)
            addAction(MainActivity.RETURN2_SETTING_CHANGED)
            addAction(MainActivity.AUTO_ACCEPT_SETTING_CHANGED)
            addAction(MainActivity.CONFIG_IMPORTED)
        }
        LocalBroadcastManager.getInstance(requireContext()).apply {
            registerReceiver(botStateReceiver, filter)
            registerReceiver(return2Receiver, IntentFilter(MainActivity.RETURN2_SETTING_CHANGED))
            registerReceiver(autoAcceptReceiver, IntentFilter(MainActivity.AUTO_ACCEPT_SETTING_CHANGED))
        }
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        val lbm = LocalBroadcastManager.getInstance(requireContext())
        try {
            lbm.unregisterReceiver(botStateReceiver)
            lbm.unregisterReceiver(return2Receiver)
            lbm.unregisterReceiver(autoAcceptReceiver)
        } catch (_: Exception) {
        }
    }

    private fun syncSwitches() {
        syncing = true
        try {
            swOverlay?.isChecked = settings.overlayEnabled
            swDryRun?.isChecked = settings.dryRunMode
            swAutoAccept?.isChecked = settings.flexAutoAccept
            swReturn2Offers?.isChecked = settings.flexAutoReturnToOffers
        } finally {
            syncing = false
        }
    }

    private fun refreshStatus() {
        val botEnabled = settings.isBotEnabled
        val paused = PichixAccessibilityService.pausedAfterAccept
        val dryRun = settings.dryRunMode
        val ctx = requireContext()
        val colorGreen = ContextCompat.getColor(ctx, R.color.green_400)
        val colorAmber = ContextCompat.getColor(ctx, R.color.amber_400)
        val colorMuted = ContextCompat.getColor(ctx, R.color.text_hint)

        val (statusText, subtitleText, statusColor) = when {
            !botEnabled -> Triple(
                "● PichiX inactivo",
                "Activa el switch del header para comenzar",
                colorMuted
            )
            paused -> Triple(
                "⏸ PichiX pausado",
                "Bloque tomado — espera el siguiente",
                colorAmber
            )
            dryRun -> Triple(
                "🧪 Modo simulación activo",
                "Evaluando bloques sin aceptarlos",
                colorAmber
            )
            else -> Triple(
                "● PichiX activo",
                "Buscando bloques en Amazon Flex…",
                colorGreen
            )
        }

        tvStatus?.text = statusText
        tvStatus?.setTextColor(statusColor)
        tvSubtitle?.text = subtitleText
        statusBar?.setBackgroundColor(statusColor)
    }
}
