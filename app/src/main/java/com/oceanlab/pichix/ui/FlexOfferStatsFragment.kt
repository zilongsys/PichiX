package com.oceanlab.pichix.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.OfferLogCsvStore
import com.oceanlab.pichix.data.OfferStatsAnalyzer
import java.util.Locale

class FlexOfferStatsFragment : Fragment() {

    private lateinit var tvSummary: TextView
    private lateinit var tvDaily: TextView
    private lateinit var tvHourly: TextView
    private lateinit var tvWeekday: TextView
    private lateinit var tvStations: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_offer_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvSummary = view.findViewById(R.id.tvOfferStatsSummary)
        tvDaily = view.findViewById(R.id.tvOfferStatsDaily)
        tvHourly = view.findViewById(R.id.tvOfferStatsHourly)
        tvWeekday = view.findViewById(R.id.tvOfferStatsWeekday)
        tvStations = view.findViewById(R.id.tvOfferStatsStations)

        view.findViewById<MaterialButton>(R.id.btnRefreshOfferStats).setOnClickListener {
            refreshStats()
        }
    }

    private fun refreshStats() {
        val entries = OfferLogCsvStore(requireContext()).readAllEntries()
        val report = OfferStatsAnalyzer.analyze(entries)
        if (report.countedOffers == 0) {
            tvSummary.text = getString(R.string.offer_stats_empty)
            tvDaily.text = "—"
            tvHourly.text = "—"
            tvWeekday.text = "—"
            tvStations.text = "—"
            return
        }

        val bestDay = report.bestDay
        val bestHour = report.bestHour
        tvSummary.text = buildString {
            appendLine("Ofertas analizadas: ${report.countedOffers} (total CSV: ${report.totalRaw})")
            if (report.dateFrom != null && report.dateTo != null) {
                appendLine("Rango: ${report.dateFrom} → ${report.dateTo}")
            }
            if (bestDay != null) {
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

        tvDaily.text = report.daily.joinToString("\n") { day ->
            "${day.date}  ${day.count} ofertas  ✓${day.accepted}  " +
                "avg ${fmtRate(day.avgRate)}  max ${fmtRate(day.maxRate)}"
        }.ifBlank { "—" }

        tvHourly.text = report.hourly
            .filter { it.count > 0 }
            .joinToString("\n") { hour ->
                "${hour.label}  ${hour.count} ofertas  avg ${fmtRate(hour.avgRate)}  max ${fmtRate(hour.maxRate)}"
            }.ifBlank { "—" }

        tvWeekday.text = report.weekdays.joinToString("\n") { wd ->
            "${wd.weekdayName}  ${wd.count} ofertas  avg ${fmtRate(wd.avgRate)}  max ${fmtRate(wd.maxRate)}"
        }.ifBlank { "—" }

        tvStations.text = report.topStations.joinToString("\n") { st ->
            "${st.station.take(28)}  ${st.count}×  avg ${fmtRate(st.avgRate)}  max ${fmtRate(st.maxRate)}"
        }.ifBlank { "—" }
    }

    private fun fmtRate(value: Double): String =
        if (value <= 0.0) "—" else String.format(Locale.US, "$%.1f", value)
}
