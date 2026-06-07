package com.oceanlab.pichix.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private lateinit var tvDaily: TextView
    private lateinit var tvHourly: TextView
    private lateinit var tvWeekdayHeader: TextView
    private lateinit var tvWeekday: TextView
    private lateinit var tvRates: TextView
    private lateinit var tvStations: TextView
    private lateinit var btnPickDate: MaterialButton
    private lateinit var toggleRange: MaterialButtonToggleGroup
    private lateinit var logger: OfferLogger

    private var rangeMode = RangeMode.ALL
    private var filterFrom: String? = null
    private var filterTo: String? = null

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_offer_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        logger = OfferLogger(requireContext())
        tvSummary = view.findViewById(R.id.tvOfferStatsSummary)
        tvRangeLabel = view.findViewById(R.id.tvOfferStatsRangeLabel)
        tvDailyHeader = view.findViewById(R.id.tvOfferStatsDailyHeader)
        tvDaily = view.findViewById(R.id.tvOfferStatsDaily)
        tvHourly = view.findViewById(R.id.tvOfferStatsHourly)
        tvWeekdayHeader = view.findViewById(R.id.tvOfferStatsWeekdayHeader)
        tvWeekday = view.findViewById(R.id.tvOfferStatsWeekday)
        tvRates = view.findViewById(R.id.tvOfferStatsRates)
        tvStations = view.findViewById(R.id.tvOfferStatsStations)
        btnPickDate = view.findViewById(R.id.btnPickOfferStatsDate)
        toggleRange = view.findViewById(R.id.toggleOfferStatsRange)

        toggleRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
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
            styleRangeToggle(checkedId)
            updateRangeUi()
        }

        styleRangeToggle(R.id.btnStatsRangeAll)

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

    private fun styleRangeToggle(checkedId: Int) {
        val ctx = requireContext()
        val activeBg = ContextCompat.getColor(ctx, R.color.tab_active_bg)
        val activeText = ContextCompat.getColor(ctx, R.color.white)
        val inactiveBg = ContextCompat.getColor(ctx, R.color.white)
        val inactiveText = ContextCompat.getColor(ctx, R.color.text_primary)
        val activeStroke = ContextCompat.getColor(ctx, R.color.accent_teal_dark)
        val inactiveStroke = ContextCompat.getColor(ctx, R.color.border_subtle)
        listOf(
            R.id.btnStatsRangeAll,
            R.id.btnStatsRangeDay,
            R.id.btnStatsRangePeriod,
        ).forEach { id ->
            val btn = view?.findViewById<MaterialButton>(id) ?: return@forEach
            val selected = id == checkedId
            btn.backgroundTintList = ColorStateList.valueOf(if (selected) activeBg else inactiveBg)
            btn.setTextColor(if (selected) activeText else inactiveText)
            btn.strokeColor = ColorStateList.valueOf(if (selected) activeStroke else inactiveStroke)
        }
    }

    private fun updateRangeUi() {
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
        tvWeekday.isVisible = rangeMode != RangeMode.SINGLE_DAY
        tvDailyHeader.text = when (rangeMode) {
            RangeMode.SINGLE_DAY -> getString(R.string.offer_stats_day_summary_header)
            else -> getString(R.string.offer_stats_daily_header)
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
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.offer_stats_pick_day))
            .setSelection(initial)
            .setCalendarConstraints(buildPastConstraints())
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val day = utcMillisToDayString(selection)
            filterFrom = day
            filterTo = day
            updateRangeUi()
        }
        picker.show(parentFragmentManager, "offer_stats_day")
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
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            filterFrom = utcMillisToDayString(selection.first)
            filterTo = utcMillisToDayString(selection.second)
            updateRangeUi()
        }
        picker.show(parentFragmentManager, "offer_stats_range")
    }

    private fun buildPastConstraints(): CalendarConstraints {
        val end = MaterialDatePicker.todayInUtcMilliseconds()
        return CalendarConstraints.Builder()
            .setEnd(end)
            .build()
    }

    private fun refreshStats() {
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
            RangeMode.SINGLE_DAY -> OfferStatsAnalyzer.DateFilter(
                from = filterFrom,
                to = filterTo ?: filterFrom,
            )
            RangeMode.RANGE -> OfferStatsAnalyzer.DateFilter(from = filterFrom, to = filterTo)
        }

        val entries = OfferLogCsvStore(requireContext()).readAllEntries()
        val report = OfferStatsAnalyzer.analyze(entries, filter)
        if (report.countedOffers == 0) {
            tvSummary.text = getString(R.string.offer_stats_no_data_for_filter)
            clearResults()
            return
        }

        val bestDay = report.bestDay
        val bestHour = report.bestHour
        tvSummary.text = buildString {
            appendLine(filterSummaryLine(report))
            appendLine("Ofertas analizadas: ${report.countedOffers} (CSV total: ${report.totalRaw})")
            if (bestDay != null && rangeMode != RangeMode.SINGLE_DAY) {
                appendLine(
                    "Mejor día: ${bestDay.date} — ${bestDay.count} ofertas, " +
                        "avg ${fmtRate(bestDay.avgRate)}/h, max ${fmtRate(bestDay.maxRate)}/h",
                )
            }
            if (bestHour != null && bestHour.count > 0) {
                appendLine(
                    "Mejor franja: ${bestHour.label} — ${bestHour.count} ofertas, " +
                        "avg ${fmtRate(bestHour.avgRate)}/h, max ${fmtRate(bestHour.maxRate)}/h",
                )
            }
            append("Top horas: ")
            append(
                report.topHours.take(5).joinToString(" · ") { h ->
                    "${h.label}(${h.count})"
                }.ifBlank { "—" },
            )
        }

        val globalMax = listOf(
            report.daily.maxOfOrNull { it.maxRate } ?: 0.0,
            report.hourly.maxOfOrNull { it.maxRate } ?: 0.0,
            report.weekdays.maxOfOrNull { it.maxRate } ?: 0.0,
            report.topStations.maxOfOrNull { it.maxRate } ?: 0.0,
            report.rateBuckets.maxOfOrNull { it.maxRate } ?: 0.0,
        ).maxOrNull() ?: 0.0

        if (rangeMode == RangeMode.SINGLE_DAY && bestDay != null) {
            tvDaily.text = formatBucketLine(
                label = bestDay.date,
                count = bestDay.count,
                avg = bestDay.avgRate,
                max = bestDay.maxRate,
                globalMaxMax = globalMax,
            )
        } else {
            tvDaily.text = report.daily.joinToString("\n") { day ->
                formatBucketLine(day.date, day.count, day.avgRate, day.maxRate, globalMax)
            }.ifBlank { "—" }
        }

        tvHourly.text = report.hourly
            .filter { it.count > 0 }
            .joinToString("\n") { hour ->
                formatBucketLine(hour.label, hour.count, hour.avgRate, hour.maxRate, globalMax)
            }.ifBlank { "—" }

        tvWeekday.text = report.weekdays.joinToString("\n") { wd ->
            formatBucketLine(wd.weekdayName, wd.count, wd.avgRate, wd.maxRate, globalMax)
        }.ifBlank { "—" }

        tvRates.text = report.rateBuckets.joinToString("\n") { bucket ->
            formatBucketLine(
                label = HourlyRateRound.label(bucket.roundedRate.toDouble()),
                count = bucket.count,
                avg = bucket.avgRate,
                max = bucket.maxRate,
                globalMaxMax = globalMax,
            )
        }.ifBlank { "—" }

        tvStations.text = report.topStations.joinToString("\n") { st ->
            formatBucketLine(
                label = st.station.take(28),
                count = st.count,
                avg = st.avgRate,
                max = st.maxRate,
                globalMaxMax = globalMax,
                countSuffix = "×",
            )
        }.ifBlank { "—" }
    }

    private fun formatBucketLine(
        label: String,
        count: Int,
        avg: Double,
        max: Double,
        globalMaxMax: Double,
        countSuffix: String = "ofertas",
    ): CharSequence {
        val ss = SpannableStringBuilder()
        val labelEnd = label.length
        ss.append(label)
        ss.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ss.append("  $count $countSuffix  avg ${fmtRate(avg)}  max ")
        val maxStart = ss.length
        val maxText = fmtRate(max)
        ss.append(maxText)
        ss.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            maxStart,
            ss.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (max > 0.0 && max >= globalMaxMax) {
            ss.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.green_400)),
                maxStart,
                ss.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return ss
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
        tvDaily.text = "—"
        tvHourly.text = "—"
        tvWeekday.text = "—"
        tvRates.text = "—"
        tvStations.text = "—"
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
        utc.set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH),
        )
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
