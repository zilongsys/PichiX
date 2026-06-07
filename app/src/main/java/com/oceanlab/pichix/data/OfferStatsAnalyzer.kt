package com.oceanlab.pichix.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object OfferStatsAnalyzer {

    data class DayBucket(
        val date: String,
        val count: Int,
        val accepted: Int,
        val avgRate: Double,
        val maxRate: Double,
    )

    data class HourBucket(
        val hour: Int,
        val count: Int,
        val avgRate: Double,
        val maxRate: Double,
        val score: Double,
    ) {
        val label: String get() = "%02d:00–%02d:59".format(hour, hour)
    }

    data class WeekdayBucket(
        val weekday: Int,
        val weekdayName: String,
        val count: Int,
        val avgRate: Double,
        val maxRate: Double,
    )

    data class StationBucket(
        val station: String,
        val count: Int,
        val avgRate: Double,
        val maxRate: Double,
    )

    data class Report(
        val totalRaw: Int,
        val countedOffers: Int,
        val dateFrom: String?,
        val dateTo: String?,
        val filterFrom: String?,
        val filterTo: String?,
        val bestDay: DayBucket?,
        val bestHour: HourBucket?,
        val topHours: List<HourBucket>,
        val daily: List<DayBucket>,
        val hourly: List<HourBucket>,
        val weekdays: List<WeekdayBucket>,
        val topStations: List<StationBucket>,
    )

    data class DateFilter(
        val from: String? = null,
        val to: String? = null,
    ) {
        val isActive: Boolean get() = !from.isNullOrBlank() || !to.isNullOrBlank()
    }

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val weekdayNames = listOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")

    fun analyze(entries: List<OfferLogEntry>, filter: DateFilter = DateFilter()): Report {
        val scoped = if (!filter.isActive) entries else entries.filter { entryInFilter(it, filter) }
        val relevant = scoped.filter { isCountable(it.status) }
        if (relevant.isEmpty()) {
            return Report(
                totalRaw = entries.size,
                countedOffers = 0,
                dateFrom = null,
                dateTo = null,
                filterFrom = filter.from,
                filterTo = filter.to,
                bestDay = null,
                bestHour = null,
                topHours = emptyList(),
                daily = emptyList(),
                hourly = emptyList(),
                weekdays = emptyList(),
                topStations = emptyList(),
            )
        }

        val dayMap = linkedMapOf<String, MutableList<OfferLogEntry>>()
        val hourBuckets = Array(24) { mutableListOf<OfferLogEntry>() }
        relevant.forEach { entry ->
            val day = dayFmt.format(entry.timestamp)
            dayMap.getOrPut(day) { mutableListOf() }.add(entry)
            hourBuckets[hourOf(entry.timestamp)].add(entry)
        }

        val daily = dayMap.entries
            .sortedByDescending { it.key }
            .map { (date, list) -> toDayBucket(date, list) }

        val hourly = (0 until 24).map { hour ->
            toHourBucket(hour, hourBuckets[hour])
        }.sortedByDescending { it.score }

        val weekdayMap = Array(7) { mutableListOf<OfferLogEntry>() }
        relevant.forEach { entry ->
            weekdayMap[weekdayOf(entry.timestamp)].add(entry)
        }
        val weekdays = weekdayMap.indices
            .map { wd ->
                val list = weekdayMap[wd]
                WeekdayBucket(
                    weekday = wd,
                    weekdayName = weekdayNames[wd],
                    count = list.size,
                    avgRate = avgRate(list),
                    maxRate = maxRate(list),
                )
            }
            .sortedByDescending { it.count }

        val stationMap = linkedMapOf<String, MutableList<OfferLogEntry>>()
        relevant.forEach { entry ->
            val key = entry.station.trim().ifBlank { "?" }
            stationMap.getOrPut(key) { mutableListOf() }.add(entry)
        }
        val topStations = stationMap.entries
            .map { (station, list) ->
                StationBucket(station, list.size, avgRate(list), maxRate(list))
            }
            .sortedWith(compareByDescending<StationBucket> { it.count }.thenByDescending { it.avgRate })
            .take(12)

        val dates = daily.map { it.date }
        return Report(
            totalRaw = entries.size,
            countedOffers = relevant.size,
            dateFrom = dates.minOrNull(),
            dateTo = dates.maxOrNull(),
            filterFrom = filter.from,
            filterTo = filter.to,
            bestDay = daily.maxByOrNull { it.count * (1.0 + it.avgRate / 100.0) },
            bestHour = hourly.firstOrNull { it.count > 0 },
            topHours = hourly.filter { it.count > 0 }.take(8),
            daily = daily,
            hourly = hourly,
            weekdays = weekdays.filter { it.count > 0 },
            topStations = topStations,
        )
    }

    private fun isCountable(status: OfferStatus): Boolean =
        status == OfferStatus.SEEN ||
            status == OfferStatus.ACCEPTED ||
            status == OfferStatus.REJECTED ||
            status == OfferStatus.SIMULATED

    private fun entryInFilter(entry: OfferLogEntry, filter: DateFilter): Boolean {
        val day = dayFmt.format(entry.timestamp)
        filter.from?.let { if (day < it) return false }
        filter.to?.let { if (day > it) return false }
        return true
    }

    private fun hourOf(ts: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    private fun weekdayOf(ts: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        return cal.get(Calendar.DAY_OF_WEEK) - 1
    }

    private fun toDayBucket(date: String, list: List<OfferLogEntry>): DayBucket {
        val accepted = list.count { it.status == OfferStatus.ACCEPTED }
        return DayBucket(
            date = date,
            count = list.size,
            accepted = accepted,
            avgRate = avgRate(list),
            maxRate = maxRate(list),
        )
    }

    private fun toHourBucket(hour: Int, list: List<OfferLogEntry>): HourBucket {
        val avg = avgRate(list)
        val max = maxRate(list)
        val score = list.size * (1.0 + avg / 50.0) + max * 0.05
        return HourBucket(hour, list.size, avg, max, score)
    }

    private fun avgRate(list: List<OfferLogEntry>): Double {
        if (list.isEmpty()) return 0.0
        val rates = list.map { it.hourlyRate }.filter { it > 0.0 }
        if (rates.isEmpty()) return 0.0
        return rates.average()
    }

    private fun maxRate(list: List<OfferLogEntry>): Double =
        list.maxOfOrNull { it.hourlyRate } ?: 0.0
}
