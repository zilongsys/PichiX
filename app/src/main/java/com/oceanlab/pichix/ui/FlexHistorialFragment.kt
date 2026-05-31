package com.oceanlab.pichix.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.OfferLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FlexHistorialFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_flex_historial, container, false)

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val tv = view?.findViewById<TextView>(R.id.tvHistorial) ?: return
        val entries = OfferLogger(requireContext()).getRecentEntries(100)
        if (entries.isEmpty()) {
            tv.text = "Sin bloques registrados hoy.\nAceptados, rechazados y simulaciones aparecerán aquí."
            return
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        tv.text = entries.joinToString("\n\n") { e ->
            val time = fmt.format(Date(e.timestampMs))
            val icon = when (e.status.name) {
                "ACCEPTED" -> "✓"
                "SIMULATED" -> "🧪"
                else -> "✗"
            }
            "$icon [$time] ${e.station.ifBlank { "—" }}\n${e.pay} — ${e.status.name}${if (e.note.isNotBlank()) " ($e.note)" else ""}"
        }
    }
}
