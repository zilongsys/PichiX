package com.oceanlab.pichix.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.oceanlab.pichix.BuildConfig
import com.oceanlab.pichix.R
import com.oceanlab.pichix.dashboardcontrol.DashboardLink
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.MonitorPackages
import com.oceanlab.pichix.update.ApkStorage
import com.oceanlab.pichix.util.OverlayPermissionHelper
import com.oceanlab.pichix.util.PermissionStatusHelper

/**
 * Página «Ajustes»: índice de secciones con un resumen de una línea de su valor actual.
 * Cada fila abre la sección a pantalla completa ([ConfigSectionActivity]).
 */
class AjustesIndexFragment : Fragment() {

    private class Row(val key: String?, val title: String, val summary: (AppSettings) -> String)
    private class Group(val title: String, val rows: List<Row>)

    private val summaryViews = mutableMapOf<String?, TextView>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_ajustes_index, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<LinearLayout>(R.id.ajustesList)
        val inflater = LayoutInflater.from(requireContext())
        summaryViews.clear()
        for (g in groups()) {
            val header = inflater.inflate(R.layout.item_ajustes_group, list, false) as TextView
            header.text = g.title
            list.addView(header)
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
            }
            g.rows.forEachIndexed { i, r ->
                val row = inflater.inflate(R.layout.item_ajustes_row, card, false)
                row.findViewById<TextView>(R.id.rowTitle).text = r.title
                summaryViews[r.key] = row.findViewById(R.id.rowSummary)
                row.findViewById<View>(R.id.rowDivider).visibility =
                    if (i == g.rows.lastIndex) View.GONE else View.VISIBLE
                row.setOnClickListener { ConfigSectionActivity.open(requireContext(), r.key, r.title) }
                card.addView(row)
            }
            list.addView(card)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun refreshSummaries() {
        if (!isAdded) return
        val settings = AppSettings(requireContext())
        for (g in groups()) for (r in g.rows) {
            val tv = summaryViews[r.key] ?: continue
            val text = r.summary(settings)
            tv.text = text
            tv.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun onOff(b: Boolean) = getString(if (b) R.string.ajustes_on else R.string.ajustes_off)
    private fun ok(b: Boolean) = if (b) "OK" else "falta"

    private fun groups(): List<Group> {
        val ctx = requireContext()
        return listOf(
            Group(
                getString(R.string.ajustes_group_basic),
                listOf(
                    Row("permisos", getString(R.string.ajustes_permisos)) {
                        getString(
                            R.string.ajustes_permisos_sum,
                            ok(PermissionStatusHelper.isAccessibilityServiceEnabled(ctx)),
                            ok(PermissionStatusHelper.isNotificationListenerEnabled(ctx)),
                            ok(OverlayPermissionHelper.canDrawOverlays(ctx)),
                        )
                    },
                    Row("amazon", getString(R.string.ajustes_amazon)) {
                        MonitorPackages.primaryTarget(ctx) ?: getString(R.string.ajustes_amazon_none)
                    },
                    Row("overlay", getString(R.string.ajustes_overlay)) { onOff(it.overlayEnabled) },
                ),
            ),
            Group(
                getString(R.string.ajustes_group_engine),
                listOf(
                    Row("click_rhythm", getString(R.string.ajustes_click_rhythm)) {
                        getString(R.string.ajustes_click_rhythm_sum, it.flexGrabIntervalMs, onOff(it.clickGoodHoursEnabled))
                    },
                    Row("flex_behavior", getString(R.string.ajustes_behavior)) {
                        getString(R.string.ajustes_behavior_sum, onOff(it.flexAutoAccept), onOff(it.flexAutoReturnToOffers))
                    },
                    Row("click_screen", getString(R.string.ajustes_click_screen)) { "" },
                    Row("pause", getString(R.string.ajustes_pause)) {
                        getString(R.string.ajustes_pause_sum, onOff(it.autoPauseAfterAccept), onOff(it.pauseByOverClicksEnabled))
                    },
                ),
            ),
            Group(
                getString(R.string.ajustes_group_data),
                listOf(
                    Row("backup", getString(R.string.ajustes_backup)) { "" },
                    Row("log", getString(R.string.ajustes_log)) {
                        getString(R.string.ajustes_log_sum, onOff(it.debugLogEnabled))
                    },
                ),
            ),
            Group(
                getString(R.string.ajustes_group_app),
                listOf(
                    Row("ui", getString(R.string.ajustes_ui)) {
                        getString(if (it.useDarkTheme) R.string.ajustes_theme_dark else R.string.ajustes_theme_light)
                    },
                    Row("update", getString(R.string.update_section_title)) {
                        val n = runCatching { ApkStorage.history(ctx).entries().size }.getOrDefault(0)
                        getString(R.string.ajustes_update_sum, BuildConfig.VERSION_NAME, n)
                    },
                    Row("dashboard", getString(R.string.ajustes_dashboard)) {
                        onOff(DashboardLink.isEnabled(ctx))
                    },
                    Row(null, getString(R.string.ajustes_all)) { getString(R.string.ajustes_all_sum) },
                ),
            ),
        )
    }
}
