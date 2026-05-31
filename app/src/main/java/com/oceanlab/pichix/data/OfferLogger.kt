package com.oceanlab.pichix.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class OfferLogger(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class LogEntry(
        val timestampMs: Long,
        val pay: String,
        val station: String,
        val status: OfferStatus,
        val note: String,
    )

    data class DayStats(
        val accepted: Int,
        val totalEarned: Double,
        val totalMiles: Double,
    )

    fun log(pay: String, station: String, status: OfferStatus, note: String = "") {
        val arr = loadTodayArray()
        val obj = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("pay", pay)
            .put("station", station)
            .put("status", status.name)
            .put("note", note)
        arr.put(obj)
        trimArray(arr, MAX_ENTRIES)
        prefs.edit().putString(todayKey(), arr.toString()).apply()
    }

    fun getRecentEntries(limit: Int = 80): List<LogEntry> {
        val arr = loadTodayArray()
        val out = mutableListOf<LogEntry>()
        for (i in arr.length() - 1 downTo 0) {
            if (out.size >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                LogEntry(
                    timestampMs = o.optLong("t"),
                    pay = o.optString("pay"),
                    station = o.optString("station"),
                    status = runCatching {
                        OfferStatus.valueOf(o.optString("status"))
                    }.getOrDefault(OfferStatus.REJECTED),
                    note = o.optString("note"),
                ),
            )
        }
        return out
    }

    fun getTodayStats(): DayStats {
        var accepted = 0
        var earned = 0.0
        val money = Regex("""\$\s*([\d,]+(?:\.\d{1,2})?)""")
        for (e in getRecentEntries(500)) {
            if (e.status == OfferStatus.ACCEPTED || e.status == OfferStatus.SIMULATED) {
                accepted++
                money.find(e.pay.replace(",", ""))?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                    earned += it
                }
            }
        }
        return DayStats(accepted = accepted, totalEarned = earned, totalMiles = 0.0)
    }

    private fun loadTodayArray(): JSONArray {
        val raw = prefs.getString(todayKey(), null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun todayKey(): String {
        val cal = Calendar.getInstance()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return KEY_PREFIX + fmt.format(cal.time)
    }

    private fun trimArray(arr: JSONArray, max: Int) {
        while (arr.length() > max) {
            arr.remove(0)
        }
    }

    companion object {
        private const val PREFS = "pichix_offer_log"
        private const val KEY_PREFIX = "log_"
        private const val MAX_ENTRIES = 200
    }
}
