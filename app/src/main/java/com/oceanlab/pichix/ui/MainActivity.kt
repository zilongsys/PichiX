package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.oceanlab.pichix.BuildConfig
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.service.BotServiceCoordinator
import com.oceanlab.pichix.service.PichixAccessibilityService
import com.oceanlab.pichix.service.PichixForegroundService

class MainActivity : AppCompatActivity() {

    lateinit var settings: AppSettings
    private lateinit var switchMain: SwitchMaterial
    private lateinit var tvStatusPill: TextView
    private lateinit var tvCountToday: TextView
    private lateinit var tvAvgRate: TextView
    private lateinit var tvTotalMiles: TextView
    lateinit var tabLayout: TabLayout

    private val tabNames = arrayOf("Home", "Config", "Tarifas", "Alertas", "Historial", "Simulador", "Revisión")
    private val dirtyTabs = mutableSetOf<Int>()
    private var isInternalSwitchUpdate = false
    private var currentTab = 0

    private val sidebarContainers = arrayOfNulls<LinearLayout>(7)
    private val sidebarIcons = arrayOfNulls<ImageView>(7)
    private val sidebarLabels = arrayOfNulls<TextView>(7)
    private var sidebarHelpContainer: LinearLayout? = null
    private var sidebarHelpIcon: ImageView? = null
    private var sidebarHelpLabel: TextView? = null
    private lateinit var viewPager: ViewPager2
    private lateinit var sidebarRoot: LinearLayout

    private val sidebarIconOutline = intArrayOf(
        R.drawable.ic_sidebar_home,
        R.drawable.ic_sidebar_config,
        R.drawable.ic_sidebar_tarifas,
        R.drawable.ic_alert_notifications,
        R.drawable.ic_sidebar_historial,
        R.drawable.ic_sidebar_simulador,
        R.drawable.ic_sidebar_revision,
    )
    private val sidebarIconFilled = intArrayOf(
        R.drawable.ic_sidebar_home_filled,
        R.drawable.ic_sidebar_config_filled,
        R.drawable.ic_sidebar_tarifas_filled,
        R.drawable.ic_alert_notifications,
        R.drawable.ic_sidebar_historial_filled,
        R.drawable.ic_sidebar_simulador,
        R.drawable.ic_sidebar_revision_filled,
    )

    private val botPausedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateHeader() }
    }
    private val botStateChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            isInternalSwitchUpdate = true
            switchMain.isChecked = settings.isBotEnabled && !PichixAccessibilityService.pausedAfterAccept
            isInternalSwitchUpdate = false
            updateHeader()
        }
    }
    private val categoryUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyCategoryUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppSettings(this)
        ThemeHelper.applyFromSettings(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupPichiXWindowInsets()

        switchMain = findViewById(R.id.switchMain)
        switchMain.styleWideSwitch(primary = true)
        tvStatusPill = findViewById(R.id.tvStatusPill)
        tvCountToday = findViewById(R.id.tvCountToday)
        tvAvgRate = findViewById(R.id.tvAvgRate)
        tvTotalMiles = findViewById(R.id.tvTotalMiles)
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        applyLogoSpan()
        bindAppVersionLabel()

        switchMain.setOnCheckedChangeRetainingFocus(window.decorView) { checked ->
            if (isInternalSwitchUpdate) return@setOnCheckedChangeRetainingFocus
            if (checked && !isAccessibilityEnabled()) {
                isInternalSwitchUpdate = true
                switchMain.isChecked = false
                isInternalSwitchUpdate = false
                showAccessibilityDialog()
                return@setOnCheckedChangeRetainingFocus
            }
            if (checked && !MonitorPackages.isConfigured(this)) {
                isInternalSwitchUpdate = true
                switchMain.isChecked = false
                isInternalSwitchUpdate = false
                Toast.makeText(this, getString(R.string.config_monitor_not_configured), Toast.LENGTH_LONG).show()
                selectSidebarItem(1)
                return@setOnCheckedChangeRetainingFocus
            }
            if (checked) PichixAccessibilityService.pausedAfterAccept = false
            settings.isBotEnabled = checked
            if (checked) {
                BotServiceCoordinator.syncForegroundService(this)
                PichixAccessibilityService.syncEngine(this)
            } else {
                PichixAccessibilityService.notifyBotDisabled()
                PichixForegroundService.stop(this)
            }
            updateHeader()
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent(BOT_STATE_CHANGED))
            if (checked) PichixForegroundService.refreshNotification(this)
        }

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 7
            override fun createFragment(pos: Int): Fragment = when (pos) {
                0 -> HomeFragment()
                1 -> FlexConfigFragment()
                2 -> FlexTarifasFragment()
                3 -> FlexAlertasFragment()
                4 -> FlexHistorialFragment()
                5 -> StubTabFragment.newInstance("Simulador", "Probar criterios sin aceptar bloques reales.")
                6 -> StubTabFragment.newInstance("Revisión", "Revisión detallada de bloques antes de aceptar.")
                else -> HomeFragment()
            }
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, pos -> tab.text = tabNames[pos] }.attach()
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = 1

        setupSidebar()
        updateHeader()
        window.decorView.post { BotServiceCoordinator.syncForegroundService(this) }
    }

    private fun applyLogoSpan() {
        val tvLogo = findViewById<TextView>(R.id.tvLogoName) ?: return
        val accent = ContextCompat.getColor(this, R.color.brand_m)
        val body = ContextCompat.getColor(this, R.color.text_primary)
        val text = "PichiX"
        val span = SpannableString(text)
        span.setSpan(ForegroundColorSpan(accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(body), 1, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(accent), 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvLogo.text = span
    }

    private fun bindAppVersionLabel() {
        findViewById<TextView>(R.id.tvAppVersion)?.text = formatVersionName(BuildConfig.VERSION_NAME)
    }

    private fun formatVersionName(raw: String): String {
        val parts = raw.trim().removePrefix("v").split(".", "-", "_")
            .mapNotNull { it.toIntOrNull() }
        return when {
            parts.size >= 3 -> "${parts[0]}.${parts[1]}.${parts[2]}"
            parts.size == 2 -> "${parts[0]}.${parts[1]}.0"
            parts.size == 1 -> "${parts[0]}.0.0"
            else -> raw.ifBlank { "0.0.0" }
        }
    }

    private fun setupSidebar() {
        val containerIds = intArrayOf(
            R.id.sidebarHome, R.id.sidebarConfig, R.id.sidebarTarifas,
            R.id.sidebarAlertas, R.id.sidebarHistorial,
            R.id.sidebarSimulador, R.id.sidebarRevision,
        )
        val iconIds = intArrayOf(
            R.id.sidebarIconHome, R.id.sidebarIconConfig, R.id.sidebarIconTarifas,
            R.id.sidebarIconAlertas, R.id.sidebarIconHistorial,
            R.id.sidebarIconSimulador, R.id.sidebarIconRevision,
        )
        val labelIds = intArrayOf(
            R.id.sidebarLabelHome, R.id.sidebarLabelConfig, R.id.sidebarLabelTarifas,
            R.id.sidebarLabelAlertas, R.id.sidebarLabelHistorial,
            R.id.sidebarLabelSimulador, R.id.sidebarLabelRevision,
        )

        for (i in 0..6) {
            sidebarContainers[i] = findViewById(containerIds[i])
            sidebarIcons[i] = findViewById(iconIds[i])
            sidebarLabels[i] = findViewById(labelIds[i])
            val idx = i
            sidebarContainers[i]?.setOnClickListener { selectSidebarItem(idx) }
        }

        sidebarRoot = findViewById(R.id.sidebar)
        sidebarHelpContainer = findViewById(R.id.sidebarHelp)
        sidebarHelpIcon = findViewById(R.id.sidebarIconHelp)
        sidebarHelpLabel = findViewById(R.id.sidebarLabelHelp)
        sidebarHelpContainer?.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        applyCategoryUi()
        selectSidebarItem(0)
    }

    fun applyCategoryUi() {
        CategoryUiHelper.applySidebarCategoryUi(
            sidebar = sidebarRoot,
            containers = sidebarContainers,
            icons = sidebarIcons,
            labels = sidebarLabels,
            helpContainer = sidebarHelpContainer,
            helpIcon = sidebarHelpIcon,
            helpLabel = sidebarHelpLabel,
            showNames = settings.showCategoryNames
        )
        selectSidebarItem(currentTab)
    }

    private fun selectSidebarItem(index: Int) {
        if (index != currentTab && dirtyTabs.contains(currentTab)) {
            Toast.makeText(
                this,
                "Cambios sin guardar en ${tabNames[currentTab]} — pulsa Guardar",
                Toast.LENGTH_SHORT
            ).show()
        }
        currentTab = index
        viewPager.setCurrentItem(index, false)

        val activeColor = ContextCompat.getColor(this, R.color.accent_teal)
        val mutedColor = ContextCompat.getColor(this, R.color.text_hint)

        for (i in 0..6) {
            val isActive = i == index
            sidebarIcons[i]?.setBackgroundResource(
                if (isActive) R.drawable.sidebar_icon_active else R.drawable.sidebar_icon_inactive
            )
            sidebarIcons[i]?.let { icon ->
                try {
                    icon.setImageResource(if (isActive) sidebarIconFilled[i] else sidebarIconOutline[i])
                } catch (_: Exception) {
                    icon.setImageResource(R.drawable.ic_sidebar_home)
                }
                ImageViewCompat.setImageTintList(
                    icon,
                    ColorStateList.valueOf(if (isActive) activeColor else mutedColor)
                )
            }
            sidebarLabels[i]?.apply {
                text = tabNames[i] + if (dirtyTabs.contains(i)) " •" else ""
                setTextColor(if (isActive) activeColor else mutedColor)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(botPausedReceiver, IntentFilter(BOT_PAUSED))
        lbm.registerReceiver(
            botStateChangedReceiver,
            IntentFilter().apply {
                addAction(BOT_STATE_CHANGED)
                addAction(PichixAccessibilityService.MOTOR_PAUSE_CHANGED)
            },
        )
        lbm.registerReceiver(categoryUiReceiver, IntentFilter(CategoryUiHelper.ACTION_CATEGORY_UI_CHANGED))
        applyCategoryUi()
        BotServiceCoordinator.syncForegroundService(this)
        com.oceanlab.pichix.service.OverlayService.sync(this)
        updateHeader()
        supportFragmentManager.fragments.forEach { f ->
            if (f is FlexConfigFragment) f.refreshAccessibilityStatus()
        }
    }

    override fun onPause() {
        super.onPause()
        val lbm = LocalBroadcastManager.getInstance(this)
        try { lbm.unregisterReceiver(botPausedReceiver) } catch (_: Exception) {}
        try { lbm.unregisterReceiver(botStateChangedReceiver) } catch (_: Exception) {}
        try { lbm.unregisterReceiver(categoryUiReceiver) } catch (_: Exception) {}
    }

    fun updateHeader() {
        val botEnabled = settings.isBotEnabled
        val paused = PichixAccessibilityService.pausedAfterAccept
        val motorPaused = PichixAccessibilityService.motorPausedForNavigation
        val dryRun = settings.dryRunMode
        val activeNow = botEnabled && !paused && !motorPaused

        isInternalSwitchUpdate = true
        switchMain.isChecked = activeNow
        isInternalSwitchUpdate = false

        tvStatusPill.text = when {
            !botEnabled -> "● Inactivo"
            paused -> "⏸ Pausado"
            motorPaused -> "⏸ Navegación manual"
            dryRun -> "🧪 SIMULACIÓN"
            else -> "● Activo — buscando"
        }
        tvStatusPill.setBackgroundResource(
            if (activeNow) R.drawable.pill_active else R.drawable.pill_inactive
        )
        tvStatusPill.setTextColor(
            when {
                !botEnabled || paused -> ContextCompat.getColor(this, R.color.text_hint)
                motorPaused || dryRun -> ContextCompat.getColor(this, R.color.amber_400)
                else -> ContextCompat.getColor(this, R.color.accent_teal)
            }
        )

        val stats = OfferLogger(this).getTodayStats()
        tvCountToday.text = stats.accepted.toString()
        tvAvgRate.text = "\$%.2f".format(stats.totalEarned)
        tvTotalMiles.text = kotlin.math.ceil(stats.totalHours).toInt().toString()
    }

    fun markDirty(tabIndex: Int) {
        if (tabIndex !in tabNames.indices) return
        dirtyTabs.add(tabIndex)
        refreshDirtyIndicators()
    }

    fun clearDirty(tabIndex: Int) {
        dirtyTabs.remove(tabIndex)
        refreshDirtyIndicators()
    }

    fun isTabDirty(tabIndex: Int): Boolean = dirtyTabs.contains(tabIndex)

    private fun refreshDirtyIndicators() {
        val activeColor = ContextCompat.getColor(this, R.color.accent_teal)
        val mutedColor = ContextCompat.getColor(this, R.color.text_hint)
        val safeCurrent = currentTab.coerceIn(tabNames.indices)
        for (i in tabNames.indices) {
            val isActive = i == safeCurrent
            sidebarLabels[i]?.apply {
                text = tabNames[i] + if (dirtyTabs.contains(i)) " •" else ""
                setTextColor(if (isActive) activeColor else mutedColor)
            }
        }
    }

    fun isAccessibilityEnabled(): Boolean {
        val enabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        } catch (_: Settings.SettingNotFoundException) {
            0
        }
        if (enabled != 1) return false
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val ourService = "$packageName/${PichixAccessibilityService::class.java.canonicalName}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(ourService, ignoreCase = true) ||
                component.startsWith("$packageName/", ignoreCase = true)
            ) return true
        }
        return false
    }

    fun showAccessibilityDialog() {
        AlertDialog.Builder(this, R.style.SparkAlertDialogTheme)
            .setTitle("Permiso de accesibilidad requerido")
            .setMessage(
                "Para que PichiX funcione activa el servicio en:\n\n" +
                    "Ajustes → Accesibilidad → PichiX → Activar"
            )
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    companion object {
        const val BOT_STATE_CHANGED = "com.oceanlab.pichix.BOT_STATE_CHANGED"
        const val BOT_PAUSED = "com.oceanlab.pichix.BOT_PAUSED"
        const val RETURN2_SETTING_CHANGED = "com.oceanlab.pichix.RETURN2_SETTING_CHANGED"
        const val EXTRA_RETURN2_ENABLED = "return2_enabled"

        fun notifyReturn2SettingChanged(context: Context, enabled: Boolean) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                Intent(RETURN2_SETTING_CHANGED).putExtra(EXTRA_RETURN2_ENABLED, enabled),
            )
        }
    }
}
