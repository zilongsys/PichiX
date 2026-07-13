package com.oceanlab.pichix.ui

import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oceanlab.pichix.R
import com.oceanlab.pichix.data.HourlyRateRound
import com.oceanlab.pichix.data.OfferLogCsvStore
import com.oceanlab.pichix.data.OfferLogEntry
import com.oceanlab.pichix.data.OfferLogger
import com.oceanlab.pichix.data.OfferStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DayLogActivity : AppCompatActivity() {

    private val csvFile by lazy {
        File(getExternalFilesDir(null) ?: filesDir, OfferLogCsvStore.FILE_NAME)
    }
    private val store by lazy { OfferLogCsvStore(this) }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dispFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    private var currentDate = dayFmt.format(Date())
    private lateinit var rv: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvEarnings: TextView
    private lateinit var tvHours: TextView
    private lateinit var filterBar: GridLayout
    private lateinit var allFilterCard: FilterCard
    private lateinit var stationFilterCard: FilterCard
    private lateinit var rateFilterCard: FilterCard
    private val filterCards = mutableMapOf<OfferStatus, FilterCard>()
    private val activeStatusFilters = OfferStatus.values().toMutableSet()
    private val activeStationFilters = mutableSetOf<String>()
    private val activeRateFilters = mutableSetOf<Int>()
    private var manualStationFilter: String = ""
    private var allEntries: List<OfferLogEntry> = emptyList()

    private data class FilterCard(
        val container: LinearLayout,
        val count: TextView,
        val countColor: Int,
    )

    private data class StatusFilter(
        val status: OfferStatus,
        val icon: String,
        val contentDescription: String,
        val countColor: Int,
    )

    private val statusFilters = listOf(
        StatusFilter(OfferStatus.SEEN, "👁", "Vistas", R.color.text_primary),
        StatusFilter(OfferStatus.ACCEPTED, "✅", "Aceptadas", R.color.green_400),
        StatusFilter(OfferStatus.REJECTED, "❌", "Rechazadas", R.color.coral_600),
        StatusFilter(OfferStatus.SIMULATED, "🧪", "Simuladas", R.color.amber_400),
        StatusFilter(OfferStatus.MISS, "💨", "Perdidas", R.color.text_hint),
        StatusFilter(OfferStatus.CANCELLED, "🚫", "Canceladas", R.color.coral_600),
    )

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importCsv(uri)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportCsv(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyFromSettings(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Log del día"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@DayLogActivity, R.color.bg_page))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val btnPrev = Button(this).apply {
            text = "◀"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
            setOnClickListener { changeDate(-1) }
        }
        tvTitle = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@DayLogActivity, R.color.text_primary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnNext = Button(this).apply {
            text = "▶"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
            setOnClickListener { changeDate(1) }
        }
        val btnExport = Button(this).apply {
            text = "↑ Export"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { exportLauncher.launch("pichix_log_$currentDate.csv") }
        }
        val btnImport = Button(this).apply {
            text = "↓ Import"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { importLauncher.launch("text/*") }
        }
        toolbar.addView(btnPrev)
        toolbar.addView(tvTitle)
        toolbar.addView(btnNext)
        toolbar.addView(btnExport)
        toolbar.addView(btnImport)
        root.addView(toolbar)

        val summaryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(ContextCompat.getColor(this@DayLogActivity, R.color.blue_bg))
        }
        tvEarnings = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@DayLogActivity, R.color.accent_teal))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        tvHours = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@DayLogActivity, R.color.accent_teal))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        summaryBar.addView(tvEarnings)
        summaryBar.addView(tvHours)
        root.addView(summaryBar)

        filterBar = GridLayout(this).apply {
            columnCount = 4
            rowCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setColumnOrderPreserved(false)
            useDefaultMargins = false
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setBackgroundColor(ContextCompat.getColor(this@DayLogActivity, R.color.bg_card))
        }
        allFilterCard = createFilterCard("📋", "Todas", R.color.accent_teal) {
            activeStatusFilters.clear()
            activeStatusFilters.addAll(OfferStatus.values())
            activeStationFilters.clear()
            manualStationFilter = ""
            activeRateFilters.clear()
            updateFilterButtons()
            updateList()
        }
        filterBar.addView(allFilterCard.container)
        statusFilters.forEach { filter ->
            val card = createFilterCard(filter.icon, filter.contentDescription, filter.countColor) {
                if (filter.status in activeStatusFilters) activeStatusFilters.remove(filter.status)
                else activeStatusFilters.add(filter.status)
                if (activeStatusFilters.isEmpty()) activeStatusFilters.add(filter.status)
                updateFilterButtons()
                updateList()
            }
            filterCards[filter.status] = card
            filterBar.addView(card.container)
        }
        stationFilterCard = createFilterCard("📍", "Estación", R.color.accent_teal) {
            showStationFilterDialog()
        }
        filterBar.addView(stationFilterCard.container)
        rateFilterCard = createFilterCard("💲", "$/h", R.color.accent_teal) {
            showRateFilterDialog()
        }
        rateFilterCard.container.layoutParams = (rateFilterCard.container.layoutParams as GridLayout.LayoutParams).apply {
            rowSpec = GridLayout.spec(2, 1f)
            columnSpec = GridLayout.spec(0, 4, 1f)
        }
        filterBar.addView(rateFilterCard.container)
        root.addView(filterBar)

        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DayLogActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(rv)
        setContentView(root)
        loadDay(currentDate)
    }

    private fun changeDate(delta: Int) {
        val cal = Calendar.getInstance().apply { time = dayFmt.parse(currentDate) ?: Date() }
        cal.add(Calendar.DAY_OF_YEAR, delta)
        currentDate = dayFmt.format(cal.time)
        loadDay(currentDate)
    }

    private fun createFilterCard(
        icon: String,
        contentDescription: String,
        countColor: Int,
        onClick: () -> Unit,
    ): FilterCard {
        val countView = TextView(this).apply {
            text = "0"
            textSize = 13f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD,
            )
            setTextColor(ContextCompat.getColor(this@DayLogActivity, countColor))
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@DayLogActivity, R.drawable.stat_card)
            isClickable = true
            isFocusable = true
            this.contentDescription = contentDescription
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(54)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener { onClick() }
            addView(TextView(this@DayLogActivity).apply {
                text = icon
                textSize = 14f
                gravity = Gravity.CENTER
            })
            addView(countView)
        }
        return FilterCard(card, countView, countColor)
    }

    private fun updateFilterButtons() {
        val allActive = activeStatusFilters.size == OfferStatus.values().size
        val counts = allEntries.groupingBy { it.status }.eachCount()
        val activeBg = ContextCompat.getColor(this, R.color.tab_active_bg)
        val inactiveBg = ContextCompat.getColor(this, R.color.white)
        val activeStroke = ContextCompat.getColor(this, R.color.accent_teal_dark)
        val inactiveStroke = ContextCompat.getColor(this, R.color.border_subtle)
        val activeText = ContextCompat.getColor(this, R.color.white)

        fun style(card: FilterCard, selected: Boolean) {
            card.container.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(if (selected) activeBg else inactiveBg)
                setStroke(dp(1), if (selected) activeStroke else inactiveStroke)
            }
            card.count.setTextColor(
                if (selected) activeText else ContextCompat.getColor(this, card.countColor),
            )
            card.container.isSelected = selected
        }

        allFilterCard.count.text = allEntries.size.toString()
        style(allFilterCard, allActive && !isStationFilterActive() && !isRateFilterActive())
        statusFilters.forEach { filter ->
            filterCards[filter.status]?.let { card ->
                card.count.text = (counts[filter.status] ?: 0).toString()
                style(card, filter.status in activeStatusFilters)
            }
        }
        val stationCount = if (isStationFilterActive()) {
            activeStationFilters.size + if (manualStationFilter.isNotBlank()) 1 else 0
        } else {
            availableStations().size
        }
        stationFilterCard.count.text = stationCount.toString()
        style(stationFilterCard, isStationFilterActive())
        val rateCount = if (isRateFilterActive()) activeRateFilters.size else availableRates().size
        rateFilterCard.count.text = rateCount.toString()
        style(rateFilterCard, isRateFilterActive())
    }

    private fun loadDay(date: String) {
        currentDate = date
        allEntries = parseEntriesForDate(date)
        val accepted = allEntries.filter { it.status == OfferStatus.ACCEPTED }
        val totalEarned = accepted.sumOf { it.price }
        val totalHours = accepted.sumOf { it.durationHours }
        tvTitle.text = "${dispFmt.format(dayFmt.parse(date) ?: Date())} — ${allEntries.size} ofertas · ${accepted.size} ✅"
        tvEarnings.text = "💰 ${"$%.2f".format(totalEarned)}"
        tvHours.text = "⏱ ${"%.1f".format(totalHours)} h"
        updateFilterButtons()
        updateList()
    }

    private fun updateList() {
        val displayed = allEntries.filter {
            it.status in activeStatusFilters &&
                matchesStationFilter(it) &&
                matchesRateFilter(it)
        }
        rv.adapter = DayLogAdapter(displayed) { entry -> confirmStatusChange(entry) }
    }

    private fun availableStations(): List<String> =
        allEntries.map { it.station.trim() }
            .filter { it.isNotBlank() && it != "—" }
            .distinctBy { it.lowercase(Locale.US) }
            .sortedBy { it.lowercase(Locale.US) }

    private fun isStationFilterActive(): Boolean =
        activeStationFilters.isNotEmpty() || manualStationFilter.isNotBlank()

    private fun matchesStationFilter(entry: OfferLogEntry): Boolean {
        if (!isStationFilterActive()) return true
        val station = entry.station.trim()
        val selectedMatch = activeStationFilters.any { it.equals(station, ignoreCase = true) }
        val manual = manualStationFilter.trim()
        val manualMatch = manual.isNotBlank() && station.contains(manual, ignoreCase = true)
        return selectedMatch || manualMatch
    }

    private fun availableRates(): List<Int> =
        allEntries.map { HourlyRateRound.rounded(it.hourlyRate) }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()

    private fun isRateFilterActive(): Boolean = activeRateFilters.isNotEmpty()

    private fun matchesRateFilter(entry: OfferLogEntry): Boolean {
        if (!isRateFilterActive()) return true
        val rate = HourlyRateRound.rounded(entry.hourlyRate)
        return rate in activeRateFilters
    }

    private fun showRateFilterDialog() {
        val rates = availableRates()
        val checks = mutableListOf<Pair<Int, CheckBox>>()
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rates.forEach { rate ->
            val check = CheckBox(this).apply {
                text = HourlyRateRound.label(rate.toDouble())
                isChecked = rate in activeRateFilters
            }
            checks += rate to check
            listContainer.addView(check)
        }
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(TextView(this@DayLogActivity).apply {
                text = "Precio por hora redondeado (20.50 → $20/h). Selecciona uno o más."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@DayLogActivity, R.color.text_secondary))
            })
            addView(
                if (rates.isEmpty()) {
                    TextView(this@DayLogActivity).apply {
                        text = "Sin tarifas en este día."
                        textSize = 13f
                    }
                } else {
                    ScrollView(this@DayLogActivity).apply {
                        addView(listContainer)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(260),
                        )
                    }
                },
            )
        }
        AlertDialog.Builder(this, R.style.SparkAlertDialogTheme)
            .setTitle("Filtrar por $/h")
            .setView(dialogView)
            .setNeutralButton("Limpiar") { _, _ ->
                activeRateFilters.clear()
                updateFilterButtons()
                updateList()
            }
            .setPositiveButton("Aplicar") { _, _ ->
                activeRateFilters.clear()
                activeRateFilters.addAll(checks.filter { it.second.isChecked }.map { it.first })
                updateFilterButtons()
                updateList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showStationFilterDialog() {
        val stations = availableStations()
        val checks = mutableListOf<Pair<String, CheckBox>>()
        val manualInput = EditText(this).apply {
            hint = "Filtro manual de estación"
            setSingleLine(true)
            setText(manualStationFilter)
        }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stations.forEach { station ->
            val check = CheckBox(this).apply {
                text = station
                isChecked = activeStationFilters.any { it.equals(station, ignoreCase = true) }
            }
            checks += station to check
            listContainer.addView(check)
        }
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(TextView(this@DayLogActivity).apply {
                text = "Selecciona estaciones del historial o escribe parte del nombre."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@DayLogActivity, R.color.text_secondary))
            })
            addView(manualInput)
            addView(ScrollView(this@DayLogActivity).apply {
                addView(listContainer)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(260),
                )
            })
        }
        AlertDialog.Builder(this, R.style.SparkAlertDialogTheme)
            .setTitle("Filtrar por estación")
            .setView(dialogView)
            .setNeutralButton("Limpiar") { _, _ ->
                activeStationFilters.clear()
                manualStationFilter = ""
                updateFilterButtons()
                updateList()
            }
            .setPositiveButton("Aplicar") { _, _ ->
                activeStationFilters.clear()
                activeStationFilters.addAll(checks.filter { it.second.isChecked }.map { it.first })
                manualStationFilter = manualInput.text?.toString()?.trim().orEmpty()
                updateFilterButtons()
                updateList()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmStatusChange(entry: OfferLogEntry) {
        val logger = OfferLogger(this)
        val options = statusFilters.map { it.status }
        var selected = options.indexOf(entry.status).coerceAtLeast(0)
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val radioIds = options.mapIndexed { index, status ->
            val id = View.generateViewId()
            radioGroup.addView(RadioButton(this).apply {
                this.id = id
                text = statusLabel(status)
                textSize = 14f
                isChecked = index == selected
            })
            id
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selected = radioIds.indexOf(checkedId).coerceAtLeast(0)
        }
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(TextView(this@DayLogActivity).apply {
                text = "Estación: ${entry.station}\n" +
                    "Precio: ${"$%.2f".format(entry.price)} · ${"%.2f".format(entry.hourlyRate)} \$/h\n\n" +
                    "Estado actual: ${statusLabel(entry.status)}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@DayLogActivity, R.color.text_primary))
            })
            addView(radioGroup)
        }
        AlertDialog.Builder(this, R.style.SparkAlertDialogTheme)
            .setTitle("Cambiar estado")
            .setView(dialogView)
            .setPositiveButton("Aplicar") { _, _ ->
                val newStatus = options[selected]
                if (newStatus == entry.status) return@setPositiveButton
                if (logger.updateEntryStatusByTimestamp(
                        entry.timestamp,
                        newStatus,
                        "Estado manual: ${statusLabel(entry.status)} -> ${statusLabel(newStatus)}",
                    )
                ) {
                    Toast.makeText(this, "✓ Estado actualizado", Toast.LENGTH_SHORT).show()
                    loadDay(currentDate)
                } else {
                    Toast.makeText(this, "Error al actualizar", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun statusLabel(status: OfferStatus): String = when (status) {
        OfferStatus.SEEN -> "👁 Vista"
        OfferStatus.ACCEPTED -> "✅ Aceptada"
        OfferStatus.REJECTED -> "❌ Rechazada"
        OfferStatus.SIMULATED -> "🧪 Simulada"
        OfferStatus.MISS -> "💨 Perdida"
        OfferStatus.CANCELLED -> "🚫 Cancelada"
    }

    private fun parseEntriesForDate(date: String): List<OfferLogEntry> {
        if (!csvFile.exists()) return emptyList()
        return try {
            csvFile.readLines().drop(1)
                .filter { it.contains(",$date") }
                .mapNotNull { store.parseLine(it) }
                .sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun exportCsv(uri: Uri) {
        try {
            val lines = csvFile.readLines()
            val header = lines.firstOrNull() ?: return
            val dayLines = lines.drop(1).filter { it.contains(",$currentDate") }
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write((header + "\n").toByteArray())
                dayLines.forEach { out.write((it + "\n").toByteArray()) }
            }
            Toast.makeText(this, "✓ Exportado ${dayLines.size} entradas", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importCsv(uri: Uri) {
        try {
            val imported = mutableListOf<String>()
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { br ->
                br.readLines().drop(1).filter { it.isNotBlank() }.forEach { imported.add(it) }
            }
            if (imported.isEmpty()) {
                Toast.makeText(this, "No hay entradas en el archivo", Toast.LENGTH_SHORT).show()
                return
            }
            val existingTimestamps = try {
                csvFile.readLines().drop(1).map { it.substringBefore(",") }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
            val newLines = imported.filter { it.substringBefore(",") !in existingTimestamps }
            if (newLines.isEmpty()) {
                Toast.makeText(this, "Todas las entradas ya existen", Toast.LENGTH_SHORT).show()
                return
            }
            csvFile.appendText(newLines.joinToString("\n") + "\n")
            Toast.makeText(this, "✓ Importadas ${newLines.size} entradas", Toast.LENGTH_SHORT).show()
            val firstDate = newLines.firstOrNull()?.split(",")?.getOrNull(1)?.take(10)
            if (firstDate != null) {
                currentDate = firstDate
                loadDay(currentDate)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

class DayLogAdapter(
    private val items: List<OfferLogEntry>,
    private val onStatusChange: ((OfferLogEntry) -> Unit)? = null,
) : RecyclerView.Adapter<DayLogAdapter.VH>() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvIndex: TextView = v.findViewById(R.id.tvOfferIndex)
        val tvStatus: TextView = v.findViewById(R.id.tvOfferStatus)
        val tvType: TextView = v.findViewById(R.id.tvOfferType)
        val tvDetail: TextView = v.findViewById(R.id.tvOfferDetail)
        val tvMeta: TextView = v.findViewById(R.id.tvOfferMeta)
        val tvReason: TextView = v.findViewById(R.id.tvOfferReason)
        val tvPrice: TextView = v.findViewById(R.id.tvOfferPrice)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_offer_log, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val e = items[pos]
        h.tvIndex.text = "#${pos + 1}"
        h.tvDetail.text = e.station.ifBlank { "—" }
        h.tvPrice.text = "$%.2f · %.1f h".format(e.price, e.durationHours)
        h.tvStatus.text = OfferLogRowUi.statusIcon(e.status)
        h.tvType.text = OfferLogRowUi.scheduleLabel(e)
        h.tvMeta.text = OfferLogRowUi.metaRightLine(e, timeFmt)
        val reason = listOf(OfferLogRowUi.reasonLeft(e), timingLine(e))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        h.tvReason.text = reason
        h.tvReason.visibility = if (reason.isBlank()) View.GONE else View.VISIBLE
        if (onStatusChange != null) {
            h.itemView.setOnLongClickListener {
                onStatusChange.invoke(e)
                true
            }
        } else {
            h.itemView.setOnLongClickListener(null)
        }
    }

    private fun timingLine(entry: OfferLogEntry): String {
        val parts = mutableListOf<String>()
        if (entry.firstSeenAt > 0L && entry.actionStartedAt > 0L) {
            parts += "Vista→acción ${formatDuration(entry.actionStartedAt - entry.firstSeenAt)}"
        }
        if (entry.actionStartedAt > 0L && entry.actionCompletedAt > 0L) {
            parts += "Acción→log ${formatDuration(entry.actionCompletedAt - entry.actionStartedAt)}"
        }
        return parts.joinToString(" · ")
    }

    private fun formatDuration(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        return if (safeMs < 1000L) "${safeMs}ms" else "%.1fs".format(safeMs / 1000.0)
    }
}
