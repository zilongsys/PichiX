package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AnalyzedNotTaken
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.AppliedSuggestionsStore
import com.oceanlab.pichix.data.FlexTariffRulesStore
import com.oceanlab.pichix.data.NotTakenAnalyzer
import com.oceanlab.pichix.data.NotTakenDiagnosis
import com.oceanlab.pichix.data.NotTakenReport
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.RuleSuggestion
import com.oceanlab.pichix.data.RuleSuggestionMatcher
import com.oceanlab.pichix.data.SuggestionState
import com.oceanlab.pichix.data.SuggestionStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Análisis de ofertas no tomadas: dónde las reglas pueden estar dejando pasar bloques buenos. */
class AnalisisFragment : Fragment() {

    private enum class Period(val label: String) {
        TODAY("hoy"),
        WEEK("últimos 7 días"),
        MONTH("últimos 30 días"),
        ALL("todo el historial"),
    }

    private var period = Period.WEEK
    private var loadToken = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rowFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val updatedFmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    private lateinit var tvStatus: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var content: LinearLayout
    private lateinit var periodButtons: Map<Period, MaterialButton>

    private val refreshRunnable = Runnable { reload() }
    private val offerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_analisis, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvStatus = view.findViewById(R.id.tvAnalisisStatus)
        tvUpdated = view.findViewById(R.id.tvAnalisisUpdated)
        view.findViewById<MaterialButton>(R.id.btnAnalisisRefresh).setOnClickListener { reload() }
        content = view.findViewById(R.id.layoutAnalisisContent)
        periodButtons = mapOf(
            Period.TODAY to view.findViewById(R.id.btnAnalisisToday),
            Period.WEEK to view.findViewById(R.id.btnAnalisisWeek),
            Period.MONTH to view.findViewById(R.id.btnAnalisisMonth),
            Period.ALL to view.findViewById(R.id.btnAnalisisAll),
        )
        periodButtons.forEach { (p, btn) ->
            btn.setOnClickListener {
                period = p
                reload()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(offerReceiver, IntentFilter(OfferLogger.ACTION_OFFER_LOGGED))
        reload()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(refreshRunnable)
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(offerReceiver)
        } catch (_: Exception) {
        }
    }

    private fun sinceMs(p: Period): Long {
        val cal = Calendar.getInstance()
        return when (p) {
            Period.TODAY -> cal.apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            Period.WEEK -> System.currentTimeMillis() - 7 * DAY_MS
            Period.MONTH -> System.currentTimeMillis() - 30 * DAY_MS
            Period.ALL -> 0L
        }
    }

    private fun reload() {
        if (!isAdded) return
        updatePeriodStyle()
        tvStatus.text = "Analizando ${period.label}…"
        val token = ++loadToken
        val appCtx = requireContext().applicationContext
        val since = sinceMs(period)
        thread(name = "AnalisisLoad", isDaemon = true) {
            val loaded = try {
                val rules = FlexTariffRulesStore.load(AppSettings(appCtx))
                val report = NotTakenAnalyzer.analyze(
                    OfferLogger(appCtx).getEntriesSince(since),
                    rules = rules,
                )
                val applied = AppliedSuggestionsStore.load(appCtx)
                report to report.suggestions.map { RuleSuggestionMatcher.status(it, rules, applied) }
            } catch (e: Exception) {
                android.util.Log.e("AnalisisFragment", "analyze: ${e.message}", e)
                null
            }
            mainHandler.post {
                if (token != loadToken || !isAdded || view == null) return@post
                if (loaded == null) {
                    tvStatus.text = "No se pudo leer el historial."
                    content.removeAllViews()
                } else {
                    val (report, statuses) = loaded
                    tvUpdated.text = buildString {
                        append("Actualizado: ${updatedFmt.format(Date(report.generatedAt))}")
                        if (report.latestEntryAt > 0L) {
                            append(" · último dato: ${rowFmt.format(Date(report.latestEntryAt))}")
                        }
                    }
                    render(report, report.suggestions.zip(statuses))
                }
            }
        }
    }

    private fun updatePeriodStyle() {
        val ctx = requireContext()
        val activeText = ContextCompat.getColor(ctx, R.color.white)
        val inactiveText = ContextCompat.getColor(ctx, R.color.text_primary)
        periodButtons.forEach { (p, btn) ->
            val active = p == period
            btn.setBackgroundResource(if (active) R.drawable.tab_active else R.drawable.tab_inactive)
            btn.setTextColor(if (active) activeText else inactiveText)
        }
    }

    private fun render(r: NotTakenReport, suggestions: List<Pair<RuleSuggestion, SuggestionStatus>>) {
        val ctx = requireContext()
        content.removeAllViews()
        if (r.uniqueNotTaken == 0) {
            tvStatus.text = "Sin ofertas no tomadas en ${period.label}."
            return
        }
        tvStatus.text = "No tomadas en ${period.label} (rechazadas, perdidas, canceladas y filtros en lista)."

        content.addView(summaryCard(ctx, r))

        if (suggestions.isNotEmpty()) content.addView(suggestionsCard(ctx, suggestions))

        if (r.nearMisses.isNotEmpty()) {
            val card = card(
                ctx,
                "Casi pasan (${r.nearMisses.size})",
                "Rechazadas por poco: cerca del límite de la regla. ★ = buena, van primero.",
            )
            r.nearMisses.take(MAX_ROWS).forEach { card.addView(offerRow(ctx, it)) }
            moreLine(ctx, card, r.nearMisses.size)
            content.addView(card)
        }

        if (r.good.isNotEmpty()) {
            val card = card(
                ctx,
                "Buenas no tomadas (${r.good.size})",
                "Pago ≥ ${NotTakenDiagnosis.money(r.goodPriceMin)} y ≥ ${NotTakenDiagnosis.hourly(r.goodHourlyMin)}" +
                    if (r.goodFromAccepted) " (según tus aceptadas)." else " (valor base; se ajusta con 5+ aceptadas).",
            )
            r.good.take(MAX_ROWS).forEach { card.addView(offerRow(ctx, it)) }
            moreLine(ctx, card, r.good.size)
            content.addView(card)
        }

        content.addView(causeCard(ctx, r))
        content.addView(priceCard(ctx, r))

        if (r.uncoveredStations.isNotEmpty()) {
            val card = card(
                ctx,
                "Estaciones sin cobertura / sin match",
                "Ofertas donde ninguna regla cubre la estación o no coincide ninguna.",
            )
            r.uncoveredStations.take(MAX_STATIONS).forEach { s ->
                card.addView(
                    labeledValue(
                        ctx,
                        s.station,
                        "${s.count} · ${NotTakenDiagnosis.money(s.minPrice)}–${NotTakenDiagnosis.money(s.maxPrice)} · " +
                            "${NotTakenDiagnosis.hourly(s.avgHourly)} prom." +
                            if (s.good > 0) " · ${s.good} buenas" else "",
                    ),
                )
            }
            content.addView(card)
        }

        if (r.missBreakdown.isNotEmpty()) {
            val card = card(
                ctx,
                "Pérdidas al tomar (${r.missBreakdown.size})",
                "Bloque no disponible, sin confirmación tras Schedule u otra pérdida.",
            )
            r.missBreakdown.take(MAX_ROWS).forEach { card.addView(offerRow(ctx, it)) }
            moreLine(ctx, card, r.missBreakdown.size)
            content.addView(card)
        }
    }

    private fun suggestionsCard(ctx: Context, items: List<Pair<RuleSuggestion, SuggestionStatus>>): LinearLayout {
        val counts = items.groupingBy { it.second.state }.eachCount()
        val summary = SuggestionState.entries.mapNotNull { st ->
            counts[st]?.let { "$it ${st.label.lowercase()}" }
        }.joinToString(" · ")
        val card = card(
            ctx,
            "Sugerencias por regla",
            "Cuánto habría entrado aflojando la condición que falló. Usa «Ir a Tarifas» para ajustar. $summary",
        )
        val ordered = items.sortedBy { (_, st) ->
            when (st.state) {
                SuggestionState.PENDING -> 0
                SuggestionState.PARTIAL -> 1
                SuggestionState.TIGHTENED -> 2
                SuggestionState.APPLIED -> 3
                SuggestionState.RULE_MISSING -> 4
            }
        }
        ordered.take(MAX_SUGGESTIONS).forEach { (s, st) ->
            val applied = st.state == SuggestionState.APPLIED
            val line = textLine(
                ctx,
                "• ${s.text()}",
                if (applied || st.state == SuggestionState.RULE_MISSING) R.color.text_hint else R.color.text_primary,
                12f,
            )
            if (applied) line.paintFlags = line.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            card.addView(line)
            statusLine(s, st)?.let { (text, color) ->
                card.addView(textLine(ctx, "   $text", color, 11f, bold = true))
            }
        }
        if (ordered.size > MAX_SUGGESTIONS) {
            card.addView(textLine(ctx, "… y ${ordered.size - MAX_SUGGESTIONS} más", R.color.text_hint, 11f))
        }
        val goTarifas = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Ir a Tarifas"
            textSize = 12f
            minimumHeight = (36 * resources.displayMetrics.density).toInt()
            setOnClickListener {
                (activity as? MainActivity)?.navigateToTab(2)
            }
        }
        card.addView(goTarifas)
        return card
    }

    private fun statusLine(s: RuleSuggestion, st: SuggestionStatus): Pair<String, Int>? {
        val now = RuleSuggestionMatcher.formatLimit(s.cause, st.currentLimit)
        val sugg = RuleSuggestionMatcher.formatLimit(s.cause, s.suggestedLimit)
        val whenApplied = st.appliedAt?.let { " · ${updatedFmt.format(Date(it))}" }.orEmpty()
        return when (st.state) {
            SuggestionState.PENDING -> null
            SuggestionState.APPLIED -> "✓ Aplicada · ahora: $now$whenApplied" to R.color.green_400
            SuggestionState.PARTIAL -> "◐ Ajuste parcial · ahora: $now (sugerido $sugg)$whenApplied" to R.color.amber_400
            SuggestionState.TIGHTENED -> "↑ Regla más estricta · ahora: $now" to R.color.red_400
            SuggestionState.RULE_MISSING -> "Regla no encontrada (se borró o cambió de nombre)" to R.color.text_hint
        }
    }

    private fun summaryCard(ctx: Context, r: NotTakenReport): LinearLayout {
        val card = card(ctx, "Resumen", null)
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(statTile(ctx, "No tomadas", "${r.uniqueNotTaken}", "${r.rawNotTakenRows} filas en historial"))
        row1.addView(statTile(ctx, "Aceptadas", "${r.accepted}", "incluye simuladas"))
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(statTile(ctx, "Valor no tomado", NotTakenDiagnosis.money(r.notTakenValue), "suma de pagos"))
        row2.addView(statTile(ctx, "Buenas / casi", "${r.good.size} / ${r.nearMisses.size}", "posibles pérdidas"))
        card.addView(row1)
        card.addView(row2)
        return card
    }

    private fun causeCard(ctx: Context, r: NotTakenReport): LinearLayout {
        val card = card(ctx, "Motivos", "Condición que falló (en la regla más cercana).")
        val max = r.byCause.maxOf { it.count }
        r.byCause.forEach { c ->
            val pct = c.count * 100 / r.uniqueNotTaken
            card.addView(textLine(ctx, "${c.cause.label} — ${c.count} ($pct%)", R.color.text_primary, 12f, bold = true))
            card.addView(bar(ctx, c.count.toFloat() / max, R.color.red_400))
            val extra = buildString {
                append("${NotTakenDiagnosis.money(c.minPrice)}–${NotTakenDiagnosis.money(c.maxPrice)}")
                append(" · prom. ${NotTakenDiagnosis.money(c.avgPrice)} · ${NotTakenDiagnosis.hourly(c.avgHourly)}")
                if (c.nearMisses > 0) append(" · ${c.nearMisses} casi pasan")
                if (c.good > 0) append(" · ${c.good} buenas")
            }
            card.addView(textLine(ctx, extra, R.color.text_secondary, 11f))
        }
        return card
    }

    private fun priceCard(ctx: Context, r: NotTakenReport): LinearLayout {
        val card = card(ctx, "Por rango de pago", "Rojo = no tomadas · verde = aceptadas.")
        val max = r.priceBuckets.maxOf { maxOf(it.notTaken, it.accepted) }.coerceAtLeast(1)
        r.priceBuckets.filter { it.notTaken + it.accepted > 0 }.forEach { b ->
            val detail = buildString {
                append("${b.notTaken} no tom. · ${b.accepted} acep.")
                if (b.notTaken > 0) append(" · ${NotTakenDiagnosis.hourly(b.avgHourlyNotTaken)} prom.")
                if (b.good > 0) append(" · ${b.good} buenas")
            }
            card.addView(labeledValue(ctx, b.label, detail))
            card.addView(bar(ctx, b.notTaken.toFloat() / max, R.color.red_400))
            if (b.accepted > 0) card.addView(bar(ctx, b.accepted.toFloat() / max, R.color.green_400))
        }
        return card
    }

    private fun offerRow(ctx: Context, a: AnalyzedNotTaken): LinearLayout {
        val d = resources.displayMetrics.density
        val o = a.offer
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 6 * d
                setColor(ContextCompat.getColor(ctx, R.color.bg_secondary))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * d).toInt() }
            addView(
                textLine(
                    ctx,
                    (if (a.good) "★ " else "") +
                        "${NotTakenDiagnosis.money(o.price)} · ${NotTakenDiagnosis.hourly(o.hourlyRate)}" +
                        if (o.durationHours > 0) " · ${"%.1f".format(o.durationHours)} h" else "",
                    R.color.brand_m,
                    13f,
                    bold = true,
                ),
            )
            addView(
                textLine(
                    ctx,
                    listOfNotNull(
                        o.station.takeIf { it.isNotBlank() },
                        o.timeWindow.takeIf { it.isNotBlank() },
                        rowFmt.format(Date(o.timestamp)),
                    ).joinToString(" · "),
                    R.color.text_secondary,
                    11f,
                ),
            )
            val rule = a.diagnosis.ruleLabel().let { if (it.isBlank()) "" else "$it: " }
            addView(textLine(ctx, rule + a.diagnosis.conditionLabel(), R.color.red_400, 11f, bold = true))
        }
    }

    private fun card(ctx: Context, title: String, subtitle: String?): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(ContextCompat.getColor(ctx, R.color.bg_card))
                setStroke((1 * d).toInt().coerceAtLeast(1), ContextCompat.getColor(ctx, R.color.border_subtle))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (8 * d).toInt() }
            addView(textLine(ctx, title, R.color.accent_teal, 14f, bold = true))
            subtitle?.let { addView(textLine(ctx, it, R.color.text_hint, 11f)) }
        }
    }

    private fun statTile(ctx: Context, label: String, value: String, hint: String): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8 * d
                setColor(ContextCompat.getColor(ctx, R.color.bg_stat_chip))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt(), 0)
            }
            addView(textLine(ctx, label, R.color.text_secondary, 11f))
            addView(textLine(ctx, value, R.color.brand_m, 17f, bold = true))
            addView(textLine(ctx, hint, R.color.text_hint, 10f))
        }
    }

    private fun labeledValue(ctx: Context, label: String, value: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            addView(textLine(ctx, label, R.color.text_primary, 12f, bold = true))
            addView(textLine(ctx, value, R.color.text_secondary, 11f))
        }

    private fun bar(ctx: Context, fraction: Float, colorRes: Int): LinearLayout {
        val d = resources.displayMetrics.density
        val f = fraction.coerceIn(0.02f, 1f)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (7 * d).toInt()).apply {
                topMargin = (3 * d).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = 4 * d
                setColor(ContextCompat.getColor(ctx, R.color.bg_secondary))
            }
            addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 4 * d
                    setColor(ContextCompat.getColor(ctx, colorRes))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, f)
            })
            if (f < 1f) {
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - f)
                })
            }
        }
    }

    private fun moreLine(ctx: Context, card: LinearLayout, total: Int) {
        if (total > MAX_ROWS) {
            card.addView(textLine(ctx, "… y ${total - MAX_ROWS} más", R.color.text_hint, 11f))
        }
    }

    private fun textLine(ctx: Context, text: String, colorRes: Int, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(ContextCompat.getColor(ctx, colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
        }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val REFRESH_DEBOUNCE_MS = 1500L
        const val MAX_ROWS = 15
        const val MAX_SUGGESTIONS = 8
        const val MAX_STATIONS = 10
    }
}
