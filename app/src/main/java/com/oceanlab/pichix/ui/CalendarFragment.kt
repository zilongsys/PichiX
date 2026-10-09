package com.oceanlab.pichix.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.AppSettings
import com.oceanlab.pichix.data.OfferLogEntry
import com.oceanlab.pichix.data.BlockDayStats
import com.oceanlab.pichix.data.OfferLogger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.round

class CalendarFragment : Fragment() {

    private enum class SummaryMode { WEEK, MONTH }

    private companion object {
        const val COLS = 7
    }

    private var logger: OfferLogger? = null
    private lateinit var settings: AppSettings
    private var displayCal: Calendar = Calendar.getInstance()
    private var selectedDay: Int = displayCal.get(Calendar.DAY_OF_MONTH)
    private var summaryMode = SummaryMode.MONTH
    private var dailyStats: Map<Int, BlockDayStats> = emptyMap()
    private var maxEarnedInMonth = 0.0

    private lateinit var tvMonthTitle: TextView
    private lateinit var tvMetaLabel: TextView
    private lateinit var tvDayDate: TextView
    private lateinit var tvDayEmpty: TextView
    private lateinit var tvDayEarned: TextView
    private lateinit var tvDayHours: TextView
    private lateinit var tvDayAccepted: TextView
    private lateinit var tvDayRate: TextView
    private lateinit var tvDayMeta: TextView
    private lateinit var calDayStatsBlock: View
    private lateinit var layoutCalDayOffers: LinearLayout
    private lateinit var tvSummaryTitle: TextView
    private lateinit var tvSumEarned: TextView
    private lateinit var tvSumAccepted: TextView
    private lateinit var tvSumHours: TextView
    private lateinit var tvSumRate: TextView
    private lateinit var btnSummaryWeek: MaterialButton
    private lateinit var btnSummaryMonth: MaterialButton
    private lateinit var etDailyMin: EditText
    private lateinit var monthGrid: CalendarMonthGridView

    private val monthTitleFmt = SimpleDateFormat("MMMM yyyy", Locale("es", "ES"))
    private val dayDetailFmt = SimpleDateFormat("d MMM yyyy", Locale("es", "ES"))

    private val offerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshCalendar()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_calendar, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext())
        logger = OfferLogger(requireContext())

        tvMonthTitle = view.findViewById(R.id.tvCalMonthTitle)
        tvMetaLabel = view.findViewById(R.id.tvCalMetaLabel)
        tvDayDate = view.findViewById(R.id.tvCalDayDate)
        tvDayEmpty = view.findViewById(R.id.tvCalDayEmpty)
        tvDayEarned = view.findViewById(R.id.tvCalDayEarned)
        tvDayHours = view.findViewById(R.id.tvCalDayHours)
        tvDayAccepted = view.findViewById(R.id.tvCalDayAccepted)
        tvDayRate = view.findViewById(R.id.tvCalDayRate)
        tvDayMeta = view.findViewById(R.id.tvCalDayMeta)
        calDayStatsBlock = view.findViewById(R.id.calDayStatsBlock)
        layoutCalDayOffers = view.findViewById(R.id.layoutCalDayOffers)
        tvSummaryTitle = view.findViewById(R.id.tvCalSummaryTitle)
        tvSumEarned = view.findViewById(R.id.tvCalSumEarned)
        tvSumAccepted = view.findViewById(R.id.tvCalSumAccepted)
        tvSumHours = view.findViewById(R.id.tvCalSumHours)
        tvSumRate = view.findViewById(R.id.tvCalSumRate)
        btnSummaryWeek = view.findViewById(R.id.btnCalSummaryWeek)
        btnSummaryMonth = view.findViewById(R.id.btnCalSummaryMonth)
        etDailyMin = view.findViewById(R.id.etCalDailyMin)
        monthGrid = view.findViewById(R.id.calMonthGrid)

        view.findViewById<MaterialButton>(R.id.btnCalOpenDayLog).visibility = View.GONE

        monthGrid.onDayClick = { day -> onDaySelected(day) }

        etDailyMin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                applyDayGoal()
                etDailyMin.clearFocus()
                true
            } else {
                false
            }
        }

        view.findViewById<MaterialButton>(R.id.btnCalPrevMonth).setOnClickListener { shiftMonth(-1) }
        view.findViewById<MaterialButton>(R.id.btnCalNextMonth).setOnClickListener { shiftMonth(+1) }
        btnSummaryWeek.setOnClickListener { setSummaryMode(SummaryMode.WEEK) }
        btnSummaryMonth.setOnClickListener { setSummaryMode(SummaryMode.MONTH) }
        view.findViewById<MaterialButton>(R.id.btnCalApplyMin).setOnClickListener { applyDayGoal() }

        refreshCalendar()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(offerReceiver, IntentFilter(OfferLogger.ACTION_OFFER_LOGGED))
        refreshCalendar()
    }

    override fun onPause() {
        super.onPause()
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(offerReceiver)
        } catch (_: Exception) {
        }
    }

    private fun weekdayOfDay(day: Int): Int = selectedDayCalendar(day).get(Calendar.DAY_OF_WEEK)

    private fun weekdayShort(dayOfWeek: Int): String = when (dayOfWeek) {
        Calendar.MONDAY -> "Lu"
        Calendar.TUESDAY -> "Ma"
        Calendar.WEDNESDAY -> "Mi"
        Calendar.THURSDAY -> "Ju"
        Calendar.FRIDAY -> "Vi"
        Calendar.SATURDAY -> "Sa"
        Calendar.SUNDAY -> "Do"
        else -> "?"
    }

    private fun goalForDay(day: Int): Double =
        settings.getCalendarWeekdayGoal(weekdayOfDay(day))

    private fun applyDayGoal() {
        val value = parseMinInput(etDailyMin.text?.toString())
        val weekday = weekdayOfDay(selectedDay)
        settings.setCalendarWeekdayGoal(weekday, value)
        monthGrid.cells = buildGridCells(
            displayCal.get(Calendar.YEAR),
            displayCal.get(Calendar.MONTH),
            displayCal.getActualMaximum(Calendar.DAY_OF_MONTH),
        )
        updateDayDetail()
    }

    private fun loadGoalIntoEditor(day: Int) {
        val weekday = weekdayOfDay(day)
        tvMetaLabel.text = "Meta ${weekdayShort(weekday)}"
        val goal = settings.getCalendarWeekdayGoal(weekday)
        etDailyMin.setText(if (goal > 0.0) formatMinInput(goal) else "")
    }

    private fun parseMinInput(raw: String?): Double {
        val t = raw?.trim()?.replace("$", "")?.replace(",", ".") ?: return 0.0
        if (t.isEmpty()) return 0.0
        return t.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    }

    private fun formatMinInput(v: Double): String =
        if (v <= 0.0) "" else round(v).toInt().toString()

    private fun shiftMonth(delta: Int) {
        displayCal.add(Calendar.MONTH, delta)
        val maxDay = displayCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        if (selectedDay > maxDay) selectedDay = maxDay
        refreshCalendar()
    }

    private fun setSummaryMode(mode: SummaryMode) {
        summaryMode = mode
        updateSummaryToggleStyle()
        updateSummary()
    }

    private fun updateSummaryToggleStyle() {
        val ctx = requireContext()
        btnSummaryWeek.setBackgroundResource(
            if (summaryMode == SummaryMode.WEEK) R.drawable.tab_active else R.drawable.tab_inactive,
        )
        btnSummaryMonth.setBackgroundResource(
            if (summaryMode == SummaryMode.MONTH) R.drawable.tab_active else R.drawable.tab_inactive,
        )
        val activeText = ContextCompat.getColor(ctx, R.color.white)
        val inactiveText = ContextCompat.getColor(ctx, R.color.text_primary)
        btnSummaryWeek.setTextColor(if (summaryMode == SummaryMode.WEEK) activeText else inactiveText)
        btnSummaryMonth.setTextColor(if (summaryMode == SummaryMode.MONTH) activeText else inactiveText)
    }

    private fun onDaySelected(day: Int) {
        selectedDay = day
        monthGrid.selectedDay = day
        loadGoalIntoEditor(day)
        updateDayDetail()
        updateSummary()
    }

    private fun refreshCalendar() {
        val logger = logger ?: return
        val year = displayCal.get(Calendar.YEAR)
        val month = displayCal.get(Calendar.MONTH)
        dailyStats = logger.getAcceptedByBlockDayForMonth(year, month)
        maxEarnedInMonth = dailyStats.values.maxOfOrNull { it.totalEarned } ?: 0.0

        val maxDay = displayCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        if (selectedDay > maxDay) selectedDay = maxDay

        tvMonthTitle.text = monthTitleFmt.format(displayCal.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "ES")) else it.toString() }

        monthGrid.cells = buildGridCells(year, month, maxDay)
        monthGrid.selectedDay = selectedDay
        monthGrid.maxEarnedInMonth = maxEarnedInMonth
        loadGoalIntoEditor(selectedDay)
        updateSummaryToggleStyle()
        updateDayDetail()
        updateSummary()
    }

    private fun buildGridCells(year: Int, month: Int, daysInMonth: Int): List<CalendarMonthGridView.DayCell?> {
        val first = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            firstDayOfWeek = Calendar.MONDAY
        }
        val offset = (first.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        val cells = mutableListOf<CalendarMonthGridView.DayCell?>()
        repeat(offset) { cells += null }
        for (day in 1..daysInMonth) {
            val earned = dailyStats[day]?.totalEarned ?: 0.0
            val goal = settings.getCalendarWeekdayGoal(
                Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 12)
                }.get(Calendar.DAY_OF_WEEK),
            )
            cells += CalendarMonthGridView.DayCell(day, earned, goal)
        }
        while (cells.size % COLS != 0) {
            cells += null
        }
        return cells
    }

    private fun updateDayDetail() {
        val stats = dailyStats[selectedDay]
        val cal = selectedDayCalendar()
        val dateLabel = dayDetailFmt.format(cal.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "ES")) else it.toString() }
        tvDayDate.text = dateLabel

        val goal = goalForDay(selectedDay)
        if (goal > 0.0) {
            val earned = stats?.totalEarned ?: 0.0
            val wd = weekdayShort(weekdayOfDay(selectedDay))
            val level = CalendarUi.goalLevel(earned, goal)
            tvDayMeta.text = "Meta $wd: ${summaryMoney(goal)} → ${CalendarUi.goalStatusLabel(level)}"
            tvDayMeta.visibility = View.VISIBLE
        } else {
            tvDayMeta.visibility = View.GONE
        }

        layoutCalDayOffers.removeAllViews()

        if (stats == null || stats.accepted == 0) {
            tvDayEmpty.visibility = View.VISIBLE
            calDayStatsBlock.visibility = View.GONE
            layoutCalDayOffers.visibility = View.GONE
            return
        }

        tvDayEmpty.visibility = View.GONE
        calDayStatsBlock.visibility = View.VISIBLE
        layoutCalDayOffers.visibility = View.VISIBLE

        val earned = stats.totalEarned
        tvDayEarned.text = "Ganado: ${summaryMoney(earned)}"
        tvDayHours.text = "Horas: ${summaryHours(stats.totalHours)}"
        tvDayAccepted.text = "Aceptadas: ${stats.accepted}"
        tvDayRate.text = "\$/h: ${summaryRate(earned, stats.totalHours)}"

        populateDayOffers(stats.offers)
    }

    private fun populateDayOffers(offers: List<OfferLogEntry>) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val padV = (4 * density).toInt()
        val padH = (6 * density).toInt()
        val marginBottom = (4 * density).toInt()
        val primary = ContextCompat.getColor(ctx, R.color.text_primary)
        val rateColor = ContextCompat.getColor(ctx, R.color.accent_teal_dark)

        offers.forEach { entry ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = marginBottom }
            }
            val station = entry.station.trim().ifBlank { "—" }
            val price = summaryMoney(entry.price)
            val schedule = OfferLogRowUi.timeWindowLabel(entry.timeWindow)
            val hourly = if (entry.hourlyRate > 0.01) {
                "${"%.2f".format(entry.hourlyRate)} \$/h"
            } else {
                summaryRate(entry.price, entry.durationHours)
            }

            row.addView(TextView(ctx).apply {
                text = "$station · $price"
                textSize = 10f
                setTextColor(primary)
                typeface = Typeface.MONOSPACE
                maxLines = 2
            })
            row.addView(TextView(ctx).apply {
                text = "$schedule · $hourly"
                textSize = 9f
                setTextColor(rateColor)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
                maxLines = 2
            })
            layoutCalDayOffers.addView(row)
        }
    }

    private fun updateSummary() {
        val stats = when (summaryMode) {
            SummaryMode.MONTH -> aggregateDays(dailyStats.keys)
            SummaryMode.WEEK -> aggregateDays(weekDaysForSelected())
        }
        tvSummaryTitle.text = when (summaryMode) {
            SummaryMode.MONTH -> "RESUMEN DEL MES"
            SummaryMode.WEEK -> weekSummaryLabel()
        }
        tvSumEarned.text = summaryMoney(stats.totalEarned)
        tvSumAccepted.text = stats.accepted.toString()
        tvSumHours.text = summaryHours(stats.totalHours)
        tvSumRate.text = summaryRate(stats.totalEarned, stats.totalHours)
    }

    private fun weekSummaryLabel(): String {
        val days = weekDaysForSelected()
        if (days.isEmpty()) return "RESUMEN DE LA SEMANA"
        val start = days.minOrNull() ?: return "RESUMEN DE LA SEMANA"
        val end = days.maxOrNull() ?: start
        return "RESUMEN SEMANA ($start–$end)"
    }

    private fun weekDaysForSelected(): Set<Int> {
        val maxDay = displayCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val year = displayCal.get(Calendar.YEAR)
        val month = displayCal.get(Calendar.MONTH)
        val cells = buildGridCells(year, month, maxDay)
        val offset = cells.indexOfFirst { it?.day == selectedDay }
        if (offset < 0) return setOf(selectedDay)
        val rowStart = (offset / COLS) * COLS
        return cells.drop(rowStart).take(COLS)
            .mapNotNull { it?.day }
            .toSet()
    }

    private data class PeriodTotals(
        val accepted: Int,
        val totalEarned: Double,
        val totalHours: Double,
    )

    private fun aggregateDays(days: Set<Int>): PeriodTotals {
        var earned = 0.0
        var accepted = 0
        var hours = 0.0
        days.forEach { day ->
            dailyStats[day]?.let { s ->
                earned += s.totalEarned
                accepted += s.accepted
                hours += s.totalHours
            }
        }
        return PeriodTotals(accepted = accepted, totalEarned = earned, totalHours = hours)
    }

    private fun selectedDayCalendar(day: Int = selectedDay): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, displayCal.get(Calendar.YEAR))
            set(Calendar.MONTH, displayCal.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
        }

    private fun summaryMoney(v: Double) = "$${round(v).toInt()}"

    private fun summaryHours(v: Double): String {
        val h = v.coerceAtLeast(0.0)
        return if (h < 0.05) "0 h" else "${"%.1f".format(h)} h"
    }

    private fun summaryRate(earned: Double, hours: Double): String =
        if (hours > 0.05) "$${"%.2f".format(earned / hours)}/h" else "—"
}
