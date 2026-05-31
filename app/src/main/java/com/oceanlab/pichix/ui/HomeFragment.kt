package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
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
    private var swReturn2Offers: SwitchMaterial? = null

    private var syncing = false
    private var suppressThemeToggle = false

    private val botStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            syncSwitches()
            refreshStatus()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
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
        swReturn2Offers = view.findViewById(R.id.homeQuickReturn2Offers)

        syncSwitches()
        setupThemeToggle(activity)

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

        swReturn2Offers?.setOnCheckedChangeRetainingFocus(view) { checked ->
            if (syncing) return@setOnCheckedChangeRetainingFocus
            settings.flexAutoReturnToOffers = checked
            PichixAccessibilityService.syncEngine(requireContext())
        }

        refreshStatus()
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
        syncSwitches()
        val filter = IntentFilter().apply {
            addAction(MainActivity.BOT_STATE_CHANGED)
            addAction(MainActivity.BOT_PAUSED)
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(botStateReceiver, filter)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(botStateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun syncSwitches() {
        syncing = true
        try {
            swOverlay?.isChecked = settings.overlayEnabled
            swDryRun?.isChecked = settings.dryRunMode
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
