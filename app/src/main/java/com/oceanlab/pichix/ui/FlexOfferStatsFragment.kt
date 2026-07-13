package com.oceanlab.pichix.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.HourlyRateRound
import com.oceanlab.pichix.data.OfferLogCsvStore
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatsAnalyzer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class FlexOfferStatsFragment : Fragment() {

    private enum class RangeMode { ALL, SINGLE_DAY, RANGE }

    private lateinit var tvSummary: TextView
    private lateinit var tvRangeLabel: TextView
    private lateinit var tvDailyHeader: TextView
    private lateinit var tvWeekdayHeader: TextView
    private lateinit var hostDaily: FrameLayout
    private lateinit var hostHourly: FrameLayout
    private lateinit var hostWeekday: FrameLayout
    private lateinit var hostRates: FrameLayout
    private lateinit var hostStations: FrameLayout
    private lateinit var btnPickDate: MaterialButton
    private lateinit var toggleRange: MaterialButtonToggleGroup
    private lateinit var scrollView: ScrollView
    private lateinit var logger: OfferLogger

    private var dailyTable: SortableStatsTableView? = null
    private var hourlyTable: SortableStatsTableView? = null
    private var weekdayTable: SortableStatsTableView? = null
    private var ratesTable: SortableStatsTableView? = null
    private var stationsTable: SortableStatsTableView? = null

    private var rangeMode = RangeMode.ALL
    private var filterFrom: String? = null
    private var filterTo: String? = null

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    private val countCols = listOf(
        SortableStatsTableView.Column("label", "Día", weight = 2.4f) { row -> row.cells["label"].orEmpty() },
        SortableStatsTableView.Column("count", "Ofertas", weight = 1.1f) { row -> row.cells["count"]?.toIntOrNull() ?: 0 },
        SortableStatsTableView.Column("ok", "Acept.", weight = 1f) { row -> row.cells["ok"]?.toIntOrNull() ?: 0 },
        SortableStatsTableView.Column("avg", "Prom.", weight = 1.2f) { row -> rateSort(row.cells["avg"]) },
        SortableStatsTableView.Column("max", "Máx.", weight = 1.2f) { row -> rateSort(row.cells["max"]) },
    )

    private val simpleCols = listOf(
        SortableStatsTableView.Column("label", "Franja", weight = 2.2f) { row -> row.cells["label"].orEmpty() },
        SortableStatsTableView.Column("count", "Ofertas", weight = 1.2f) { row -> row.cells["count"]?.toIntOrNull() ?: 0 },
        SortableStatsTableView.Column("avg", "Prom.", weight = 1.3f) { row -> rateSort(row.cells["avg"]) },
        SortableStatsTableView.Column("max", "Máx.", weight = 1.3f) { row -> rateSort(row.cells["max"]) },
    )

    private val stationCols = listOf(
        SortableStatsTableView.Column("label", "Estación", weight = 2.8f) { row -> row.cells["label"].orEmpty() },
        SortableStatsTableView.Column("count", "Ofertas", weight = 1.1f) { row -> row.cells["count"]?.toIntOrNull() ?: 0 },
        SortableStatsTableView.Column("avg", "Prom.", weight = 1.2f) { row -> rateSort(row.cells["avg"]) },
        SortableStatsTableView.Column("max", "Máx.", weight = 1.2f) { row -> rateSort(row.cells["max"]) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_offer_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        logger = OfferLogger(requireContext())
        scrollView = view.findViewById(R.id.scrollOfferStats)
        scrollView.setupFormFocus()
        tvSummary = view.findViewById(R.id.tvOfferStatsSummary)
        tvRangeLabel = view.findViewById(R.id.tvOfferStatsRangeLabel)
        tvDailyHeader = view.findViewById(R.id.tvOfferStatsDailyHeader)
        tvWeekdayHeader = view.findViewById(R.id.tvOfferStatsWeekdayHeader)
        hostDaily = view.findViewById(R.id.statsDailyTableHost)
        hostHourly = view.findViewById(R.id.statsHourlyTableHost)
        hostWeekday = view.findViewById(R.id.statsWeekdayTableHost)
        hostRates = view.findViewById(R.id.statsRatesTableHost)
        hostStations = view.findViewById(R.id.statsStationsTableHost)
        btnPickDate = view.findViewById(R.id.btnPickOfferStatsDate)
        toggleRange = view.findViewById(R.id.toggleOfferStatsRange)

        dailyTable = ensureTable(hostDaily)
        hourlyTable = ensureTable(hostHourly)
        weekdayTable = ensureTable(hostWeekday)
        ratesTable = ensureTable(hostRates)
        stationsTable = ensureTable(hostStations)

        toggleRange.check(R.id.btnStatsRangeAll)
        SegmentedToggleStyle.wireGroup(toggleRange, scrollView) { checkedId ->
            rangeMode = when (checkedId) {
                R.id.btnStatsRangeDay -> RangeMode.SINGLE_DAY
                R.id.btnStatsRangePeriod -> RangeMode.RANGE
                else -> RangeMode.ALL
            }
            if (rangeMode == RangeMode.ALL) {
                filterFrom = null
                filterTo = null
            } else if (rangeMode == RangeMode.SINGLE_DAY && filterFrom.isNullOrBlank()) {
                val today = dayFmt.format(Date())
                filterFrom = today
                filterTo = today
            }
            updateRangeUi()
        }
        btnPickDate.setOnClickListener {
            when (rangeMode) {
                RangeMode.SINGLE_DAY -> showSingleDayPicker()
                RangeMode.RANGE -> showRangePicker()
                RangeMode.ALL -> Unit
            }
        }

        view.findViewById<MaterialButton>(R.id.btnRefreshOfferStats).setOnClickListener {
            refreshStats()
        }
        view.findViewById<MaterialButton>(R.id.btnStatsExportAll).setOnClickListener {
            shareCsvFile(logger.getLogFilePath())
        }
        view.findViewById<MaterialButton>(R.id.btnStatsClearToday).setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
                .setTitle(R.string.offer_stats_clear_today_title)
                .setMessage(R.string.offer_stats_clear_today_message)
                .setPositiveButton(R.string.offer_stats_clear_today) { _, _ ->
                    logger.resetToday()
                    refreshStats()
                    Toast.makeText(requireContext(), R.string.offer_stats_cleared_today, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        view.findViewById<MaterialButton>(R.id.btnStatsClearAll).setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.SparkAlertDialogTheme)
                .setTitle(R.string.offer_stats_clear_all_title)
                .setMessage(R.string.offer_stats_clear_all_message)
                .setPositiveButton(R.string.offer_stats_clear_all) { _, _ ->
                    logger.clearHistory()
                    refreshStats()
                    Toast.makeText(requireContext(), R.string.offer_stats_cleared_all, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        updateRangeUi()
    }

    private fun ensureTable(host: FrameLayout): SortableStatsTableView {
        host.removeAllViews()
        return SortableStatsTableView(requireContext()).also { host.addView(it) }
    }

    private fun rateSort(raw: String?): Double {
        if (raw.isNullOrBlank() || raw == "—") return 0.0
        val cleaned = raw.removePrefix("$").removeSuffix("/h").trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun updateRangeUi() {
        scrollView.runRetainingScrollAndFocus {
            val showPicker = rangeMode != RangeMode.ALL
            btnPickDate.isVisible = showPicker
            btnPickDate.text = when (rangeMode) {
                RangeMode.SINGLE_DAY -> getString(R.string.offer_stats_pick_day)
                RangeMode.RANGE -> getString(R.string.offer_stats_pick_range)
                RangeMode.ALL -> ""
            }
            tvRangeLabel.text = when (rangeMode) {
                RangeMode.ALL -> getString(R.string.offer_stats_range_all_label)
                RangeMode.SINGLE_DAY -> formatSingleDayLabel(filterFrom)
                RangeMode.RANGE -> formatRangeLabel(filterFrom, filterTo)
            }
            tvWeekdayHeader.isVisible = rangeMode != RangeMode.SINGLE_DAY
            hostWeekday.isVisible = rangeMode != RangeMode.SINGLE_DAY
            tvDailyHeader.text = when (rangeMode) {
                RangeMode.SINGLE_DAY -> getString(R.string.offer_stats_day_summary_header)
                else -> getString(R.string.offer_stats_daily_header)
            }
        }
    }

    private fun formatSingleDayLabel(day: String?): String {
        if (day.isNullOrBlank()) return getString(R.string.offer_stats_pick_day_hint)
        val parsed = dayFmt.parse(day)
        val label = parsed?.let { displayFmt.format(it) } ?: day
        return getString(R.string.offer_stats_selected_day, label)
    }

    private fun formatRangeLabel(from: String?, to: String?): String {
        if (from.isNullOrBlank() || to.isNullOrBlank()) {
            return getString(R.string.offer_stats_pick_range_hint)
        }
        val fromLabel = dayFmt.parse(from)?.let { displayFmt.format(it) } ?: from
        val toLabel = dayFmt.parse(to)?.let { displayFmt.format(it) } ?: to
        return getString(R.string.offer_stats_selected_range, fromLabel, toLabel)
    }

    private fun showSingleDayPicker() {
        val initial = dayStringToUtcMillis(filterFrom ?: dayFmt.format(Date()))
        MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.offer_stats_pick_day))
            .setSelection(initial)
            .setCalendarConstraints(buildPastConstraints())
            .build()
            .apply {
                addOnPositiveButtonClickListener { selection ->
                    filterFrom = utcMillisToDayString(selection)
                    filterTo = filterFrom
                    updateRangeUi()
                }
                show(parentFragmentManager, "offer_stats_day")
            }
    }

    private fun showRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.offer_stats_pick_range))
            .setCalendarConstraints(buildPastConstraints())
        if (!filterFrom.isNullOrBlank() && !filterTo.isNullOrBlank()) {
            builder.setSelection(
                androidx.core.util.Pair(
                    dayStringToUtcMillis(filterFrom!!),
                    dayStringToUtcMillis(filterTo!!),
                ),
            )
        }
        builder.build().apply {
            addOnPositiveButtonClickListener { selection ->
                filterFrom = utcMillisToDayString(selection.first)
                filterTo = utcMillisToDayString(selection.second)
                updateRangeUi()
            }
            show(parentFragmentManager, "offer_stats_range")
        }
    }

    private fun buildPastConstraints(): CalendarConstraints =
        CalendarConstraints.Builder()
            .setEnd(MaterialDatePicker.todayInUtcMilliseconds())
            .build()

    private fun refreshStats() {
        scrollView.runRetainingScrollAndFocus {
            refreshStatsBody()
        }
    }

    private fun refreshStatsBody() {
        if (rangeMode == RangeMode.SINGLE_DAY && filterFrom.isNullOrBlank()) {
            tvSummary.text = getString(R.string.offer_stats_pick_day_hint)
            clearResults()
            return
        }
        if (rangeMode == RangeMode.RANGE && (filterFrom.isNullOrBlank() || filterTo.isNullOrBlank())) {
            tvSummary.text = getString(R.string.offer_stats_pick_range_hint)
            clearResults()
            return
        }

        val filter = when (rangeMode) {
            RangeMode.ALL -> OfferStatsAnalyzer.DateFilter()
            RangeMode.SINGLE_DAY -> OfferStatsAnalyzer.DateFilter(from = filterFrom, to = filterTo ?: filterFrom)
            RangeMode.RANGE -> OfferStatsAnalyzer.DateFilter(from = filterFrom, to = filterTo)
        }

        val report = OfferStatsAnalyzer.analyze(OfferLogCsvStore(requireContext()).readAllEntries(), filter)
        if (report.countedOffers == 0) {
            tvSummary.text = getString(R.string.offer_stats_no_data_for_filter)
            clearResults()
            return
        }

        val globalMax = listOf(
            report.daily.maxOfOrNull { it.maxRate } ?: 0.0,
            report.hourly.maxOfOrNull { it.maxRate } ?: 0.0,
            report.weekdays.maxOfOrNull { it.maxRate } ?: 0.0,
            report.topStations.maxOfOrNull { it.maxRate } ?: 0.0,
            report.rateBuckets.maxOfOrNull { it.maxRate } ?: 0.0,
        ).maxOrNull() ?: 0.0

        val bestDay = report.bestDay
        val bestHour = report.bestHour
        tvSummary.text = buildSummaryText(report, bestDay, bestHour)

        val dailyRows = if (rangeMode == RangeMode.SINGLE_DAY && bestDay != null) {
            listOf(bestDay)
        } else {
            report.daily
        }.map { day ->
            SortableStatsTableView.Row(
                mapOf(
                    "label" to day.date,
                    "count" to day.count.toString(),
                    "ok" to day.accepted.toString(),
                    "avg" to fmtRate(day.avgRate),
                    "max" to fmtRate(day.maxRate),
                ),
                maxRate = day.maxRate,
            )
        }
        dailyTable?.bind(countCols, dailyRows, globalMax)

        val hourlyRows = report.hourly
            .filter { it.count > 0 }
            .map { hour ->
                SortableStatsTableView.Row(
                    mapOf(
                        "label" to hour.label,
                        "count" to hour.count.toString(),
                        "avg" to fmtRate(hour.avgRate),
                        "max" to fmtRate(hour.maxRate),
                    ),
                    maxRate = hour.maxRate,
                )
            }
        hourlyTable?.bind(simpleCols, hourlyRows, globalMax)

        val weekdayRows = report.weekdays.map { wd ->
            SortableStatsTableView.Row(
                mapOf(
                    "label" to wd.weekdayName,
                    "count" to wd.count.toString(),
                    "avg" to fmtRate(wd.avgRate),
                    "max" to fmtRate(wd.maxRate),
                ),
                maxRate = wd.maxRate,
            )
        }
        weekdayTable?.bind(simpleCols, weekdayRows, globalMax)

        val rateRows = report.rateBuckets.map { bucket ->
            SortableStatsTableView.Row(
                mapOf(
                    "label" to HourlyRateRound.label(bucket.roundedRate.toDouble()),
                    "count" to bucket.count.toString(),
                    "avg" to fmtRate(bucket.avgRate),
                    "max" to fmtRate(bucket.maxRate),
                ),
                maxRate = bucket.maxRate,
            )
        }
        ratesTable?.bind(
            listOf(
                SortableStatsTableView.Column("label", "$/h", weight = 1.4f) { row -> rateSort(row.cells["label"]) },
                SortableStatsTableView.Column("count", "Ofertas", weight = 1.2f) { row -> row.cells["count"]?.toIntOrNull() ?: 0 },
                SortableStatsTableView.Column("avg", "Prom.", weight = 1.3f) { row -> rateSort(row.cells["avg"]) },
                SortableStatsTableView.Column("max", "Máx.", weight = 1.3f) { row -> rateSort(row.cells["max"]) },
            ),
            rateRows,
            globalMax,
        )

        val stationRows = report.topStations.map { st ->
            SortableStatsTableView.Row(
                mapOf(
                    "label" to st.stationCode,
                    "count" to st.count.toString(),
                    "avg" to fmtRate(st.avgRate),
                    "max" to fmtRate(st.maxRate),
                ),
                maxRate = st.maxRate,
            )
        }
        stationsTable?.bind(stationCols, stationRows, globalMax)
    }

    private fun buildSummaryText(
        report: OfferStatsAnalyzer.Report,
        bestDay: OfferStatsAnalyzer.DayBucket?,
        bestHour: OfferStatsAnalyzer.HourBucket?,
    ): CharSequence {
        val sb = SpannableStringBuilder()
        fun appendLine(label: String, value: String) {
            val start = sb.length
            sb.append(label)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
            sb.append(' ')
            sb.append(value)
            sb.append('\n')
            sb.append('\n')
        }
        appendLine("Periodo:", filterSummaryLine(report))
        appendLine(
            "Ofertas analizadas:",
            "${report.countedOffers} (CSV total: ${report.totalRaw})",
        )
        if (bestDay != null && rangeMode != RangeMode.SINGLE_DAY) {
            appendLine(
                "Mejor día:",
                "${bestDay.date} — ${bestDay.count} ofertas, " +
                    "avg ${fmtRate(bestDay.avgRate)}/h, max ${fmtRate(bestDay.maxRate)}/h",
            )
        }
        if (bestHour != null && bestHour.count > 0) {
            appendLine(
                "Mejor franja:",
                "${bestHour.label} — ${bestHour.count} ofertas, " +
                    "avg ${fmtRate(bestHour.avgRate)}/h, max ${fmtRate(bestHour.maxRate)}/h",
            )
        }
        val topStart = sb.length
        sb.append("Top horas: ")
        sb.setSpan(StyleSpan(Typeface.BOLD), topStart, sb.length, 0)
        sb.append(
            report.topHours.take(5).joinToString(" · ") { "${it.label}(${it.count})" }.ifBlank { "—" },
        )
        return sb
    }

    private fun filterSummaryLine(report: OfferStatsAnalyzer.Report): String =
        when (rangeMode) {
            RangeMode.ALL -> getString(R.string.offer_stats_filter_all)
            RangeMode.SINGLE_DAY -> getString(
                R.string.offer_stats_filter_day,
                formatSingleDayLabel(report.filterFrom ?: filterFrom),
            )
            RangeMode.RANGE -> {
                val from = report.filterFrom ?: filterFrom.orEmpty()
                val to = report.filterTo ?: filterTo.orEmpty()
                val fromLabel = dayFmt.parse(from)?.let { displayFmt.format(it) } ?: from
                val toLabel = dayFmt.parse(to)?.let { displayFmt.format(it) } ?: to
                getString(R.string.offer_stats_filter_range, fromLabel, toLabel)
            }
        }

    private fun clearResults() {
        dailyTable?.bind(countCols, emptyList(), 0.0)
        hourlyTable?.bind(simpleCols, emptyList(), 0.0)
        weekdayTable?.bind(simpleCols, emptyList(), 0.0)
        ratesTable?.bind(simpleCols, emptyList(), 0.0)
        stationsTable?.bind(stationCols, emptyList(), 0.0)
    }

    private fun shareCsvFile(path: String) {
        try {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(requireContext(), R.string.offer_stats_no_csv, Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.offer_stats_export_all),
                ),
            )
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dayStringToUtcMillis(day: String): Long {
        val parsed = dayFmt.parse(day) ?: return MaterialDatePicker.todayInUtcMilliseconds()
        val local = Calendar.getInstance()
        local.time = parsed
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        return utc.timeInMillis
    }

    private fun utcMillisToDayString(millis: Long): String {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = millis
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            utc.get(Calendar.YEAR),
            utc.get(Calendar.MONTH) + 1,
            utc.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun fmtRate(value: Double): String =
        if (value <= 0.0) "—" else String.format(Locale.US, "$%.1f", value)
}
