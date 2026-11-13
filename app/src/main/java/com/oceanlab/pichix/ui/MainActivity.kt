package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.oceanlab.pichix.BuildConfig
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.BotEventLog
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

    /** Nombre de cada página del ViewPager (índices estables: 0 Inicio, 1 Ajustes, 2 Tarifas, 3 Alertas, 4 Historial, 5 Calendario, 6 Estadísticas, 7 No tomadas, 8 Log). */
    private val tabNames = arrayOf(
        "Inicio", "Ajustes", "Tarifas", "Alertas", "Historial", "Calendario",
        "Estadísticas", "No tomadas", "Log bot",
    )
    private val dirtyTabs = mutableSetOf<Int>()
    private var isInternalSwitchUpdate = false
    private var currentTab = 0

    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private var isInternalNavUpdate = false

    /** Páginas del ViewPager que agrupa cada destino de la barra inferior (mismo orden que el menú). */
    private val destPages = arrayOf(
        intArrayOf(0),
        intArrayOf(2, 3),
        intArrayOf(4, 5, 8),
        intArrayOf(6, 7),
        intArrayOf(1),
    )
    private val destMenuIds = intArrayOf(
        R.id.nav_home, R.id.nav_rules, R.id.nav_activity, R.id.nav_analysis, R.id.nav_settings,
    )
    private val lastPageOfDest = IntArray(destPages.size) { destPages[it][0] }
    private var shownDest = -1

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
                selectPage(1)
                return@setOnCheckedChangeRetainingFocus
            }
            if (checked) {
                PichixAccessibilityService.pausedAfterAccept = false
                // Activar el bot debe reanudar el motor: si quedó en «Navegación manual»
                // (FAB ⏸ o comando PC), el switch parecía no encender y el cartel naranja
                // seguía bloqueando clics/scroll.
                PichixAccessibilityService.setMotorPausedForNavigation(false, this)
            }
            settings.isBotEnabled = checked
            BotEventLog.log(
                this,
                BotEventLog.CAT_BOT,
                if (checked) "Bot activado (usuario)" else "Bot desactivado (usuario)",
            )
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
            override fun getItemCount() = TAB_COUNT
            override fun createFragment(pos: Int): Fragment = when (pos) {
                0 -> HomeFragment()
                1 -> FlexConfigFragment()
                2 -> FlexTarifasFragment()
                3 -> FlexAlertasFragment()
                4 -> FlexHistorialFragment()
                5 -> CalendarFragment()
                6 -> FlexOfferStatsFragment()
                7 -> AnalisisFragment()
                8 -> FlexBotLogFragment()
                else -> HomeFragment()
            }
        }
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = 1

        setupNavigation()
        savedInstanceState?.getInt(STATE_PAGE, -1)?.takeIf { it >= 0 }?.let { selectPage(it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Atrás desde cualquier destino vuelve a Inicio; desde Inicio sale de la app.
                if (currentTab != 0) {
                    selectPage(0)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        updateHeader()
        window.decorView.post { BotServiceCoordinator.syncForegroundService(this) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentTab)
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

    private fun destOfPage(page: Int): Int = destPages.indexOfFirst { page in it }.coerceAtLeast(0)

    private fun setupNavigation() {
        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            if (isInternalNavUpdate) return@setOnItemSelectedListener true
            val dest = destMenuIds.indexOf(item.itemId)
            if (dest < 0) return@setOnItemSelectedListener false
            selectPage(lastPageOfDest[dest])
            true
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (isInternalNavUpdate) return
                val pages = destPages[shownDest.coerceAtLeast(0)]
                pages.getOrNull(tab.position)?.let { selectPage(it) }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        findViewById<View>(R.id.btnHeaderHelp)?.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        applyCategoryUi()
        selectPage(0)
    }

    /** Muestra u oculta los nombres bajo los iconos según «Mostrar nombres en la barra inferior». */
    fun applyCategoryUi() {
        if (!::bottomNav.isInitialized) return
        bottomNav.labelVisibilityMode =
            if (settings.showCategoryNames) NavigationBarView.LABEL_VISIBILITY_LABELED
            else NavigationBarView.LABEL_VISIBILITY_UNLABELED
        selectPage(currentTab)
    }

    fun navigateToTab(index: Int) = selectPage(index)

    private fun selectPage(index: Int) {
        if (index !in 0 until TAB_COUNT) return
        if (index != currentTab && dirtyTabs.contains(currentTab)) {
            Toast.makeText(
                this,
                "Cambios sin guardar en ${tabNames[currentTab]} — pulsa Guardar",
                Toast.LENGTH_SHORT
            ).show()
        }
        currentTab = index
        viewPager.setCurrentItem(index, false)

        val dest = destOfPage(index)
        lastPageOfDest[dest] = index
        isInternalNavUpdate = true
        try {
            bottomNav.menu.findItem(destMenuIds[dest])?.isChecked = true
            if (shownDest != dest) {
                rebuildSubTabs(dest)
                shownDest = dest
            }
            val pos = destPages[dest].indexOf(index)
            if (pos >= 0 && tabLayout.selectedTabPosition != pos) tabLayout.getTabAt(pos)?.select()
        } finally {
            isInternalNavUpdate = false
        }
        refreshDirtyIndicators()
    }

    private fun rebuildSubTabs(dest: Int) {
        tabLayout.removeAllTabs()
        val pages = destPages[dest]
        pages.forEach { p -> tabLayout.addTab(tabLayout.newTab().setText(tabNames[p]), false) }
        tabLayout.visibility = if (pages.size > 1) View.VISIBLE else View.GONE
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
        tvTotalMiles.text =
            if (stats.totalHours > 0.01) "%.1f h".format(stats.totalHours) else "0 h"
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

    fun reloadSettingsFromDisk() {
        settings = AppSettings(this)
    }

    /** Tras importar config: recarga ajustes en memoria y refresca pestañas visibles. */
    fun notifyConfigImported() {
        reloadSettingsFromDisk()
        applyCategoryUi()
        updateHeader()
        clearDirty(1)
        walkFragments { fragment ->
            if (fragment is FlexConfigFragment && fragment.isAdded) {
                try {
                    fragment.reloadConfigFieldsFromSettings()
                } catch (_: Exception) {
                }
            }
        }
        AppSettings.clearPendingConfigUiReload()
        LocalBroadcastManager.getInstance(this).apply {
            sendBroadcast(Intent(CONFIG_IMPORTED))
            sendBroadcast(Intent(BOT_STATE_CHANGED))
            sendBroadcast(Intent(CategoryUiHelper.ACTION_CATEGORY_UI_CHANGED))
        }
    }

    /** Guarda el formulario Config en disco antes de exportar (si la pestaña ya existe). */
    fun flushConfigFormBeforeExport() {
        walkFragments { fragment ->
            if (fragment is FlexConfigFragment) {
                fragment.saveAllFieldsToSettings()
            }
        }
    }

    private fun walkFragments(action: (Fragment) -> Unit) {
        val pending = ArrayDeque<Fragment>()
        pending.addAll(supportFragmentManager.fragments)
        while (pending.isNotEmpty()) {
            val fragment = pending.removeFirst()
            action(fragment)
            pending.addAll(fragment.childFragmentManager.fragments)
        }
    }

    fun isTabDirty(tabIndex: Int): Boolean = dirtyTabs.contains(tabIndex)

    /** Punto en el destino y « •» en la sub-pestaña de las páginas con cambios sin guardar. */
    private fun refreshDirtyIndicators() {
        if (!::bottomNav.isInitialized) return
        for (d in destPages.indices) {
            val dirty = destPages[d].any { dirtyTabs.contains(it) }
            if (dirty) bottomNav.getOrCreateBadge(destMenuIds[d])
            else bottomNav.removeBadge(destMenuIds[d])
        }
        if (shownDest < 0) return
        val pages = destPages[shownDest]
        for (i in pages.indices) {
            tabLayout.getTabAt(i)?.text = tabNames[pages[i]] + if (dirtyTabs.contains(pages[i])) " •" else ""
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
        const val TAB_COUNT = 9
        private const val STATE_PAGE = "pichix_page"
        const val BOT_STATE_CHANGED = "com.oceanlab.pichix.BOT_STATE_CHANGED"
        const val BOT_PAUSED = "com.oceanlab.pichix.BOT_PAUSED"
        const val RETURN2_SETTING_CHANGED = "com.oceanlab.pichix.RETURN2_SETTING_CHANGED"
        const val EXTRA_RETURN2_ENABLED = "return2_enabled"
        const val AUTO_ACCEPT_SETTING_CHANGED = "com.oceanlab.pichix.AUTO_ACCEPT_SETTING_CHANGED"
        const val EXTRA_AUTO_ACCEPT_ENABLED = "auto_accept_enabled"
        const val CONFIG_IMPORTED = "com.oceanlab.pichix.CONFIG_IMPORTED"

        fun notifyReturn2SettingChanged(context: Context, enabled: Boolean) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                Intent(RETURN2_SETTING_CHANGED).putExtra(EXTRA_RETURN2_ENABLED, enabled),
            )
        }

        fun notifyAutoAcceptSettingChanged(context: Context, enabled: Boolean) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                Intent(AUTO_ACCEPT_SETTING_CHANGED).putExtra(EXTRA_AUTO_ACCEPT_ENABLED, enabled),
            )
        }
    }
}
