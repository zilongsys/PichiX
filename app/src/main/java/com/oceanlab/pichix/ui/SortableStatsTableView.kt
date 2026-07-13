package com.oceanlab.pichix.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.oceanlab.pichix.R

/**
 * Tabla a ancho completo con bordes, filas alternas y cabeceras ordenables.
 * Usa LinearLayout (no RecyclerView) para no provocar saltos de scroll en ScrollView padre.
 */
class SortableStatsTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Column(
        val id: String,
        val title: String,
        val weight: Float = 1f,
        val sortKey: (Row) -> Comparable<*> = { row -> row.cells[id].orEmpty() },
    )

    data class Row(
        val cells: Map<String, String>,
        val maxRate: Double = 0.0,
    )

    private val headerRow: LinearLayout
    private val rowsContainer: LinearLayout
    private var columns: List<Column> = emptyList()
    private var rows: List<Row> = emptyList()
    private var sortColumnId: String? = null
    private var sortAscending = true
    private var globalMaxMax = 0.0
    private val cellPadH = (6 * resources.displayMetrics.density).toInt()
    private val cellPadV = (5 * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.widget_sortable_stats_table, this, true)
        headerRow = findViewById(R.id.statsTableHeaderRow)
        rowsContainer = findViewById(R.id.statsTableRowsContainer)
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun bind(cols: List<Column>, data: List<Row>, highlightMaxRate: Double) {
        runRetainingHostScroll(this) {
            columns = cols
            rows = data
            globalMaxMax = highlightMaxRate
            if (sortColumnId == null && cols.isNotEmpty()) {
                sortColumnId = cols.first().id
            }
            renderHeader()
            renderRows()
        }
    }

    private fun renderHeader() {
        headerRow.removeAllViews()
        columns.forEach { col ->
            val tv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, col.weight)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(cellPadH, cellPadV + 2, cellPadH, cellPadV + 2)
                setBackgroundResource(R.drawable.stats_table_header_cell)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = false
                isFocusableInTouchMode = false
            }
            val active = col.id == sortColumnId
            tv.text = if (active) {
                "${col.title}${if (sortAscending) " ▲" else " ▼"}"
            } else {
                col.title
            }
            if (active) {
                tv.setTextColor(ContextCompat.getColor(context, R.color.tab_active_bg))
            }
            tv.setOnClickListener {
                runRetainingHostScroll(this@SortableStatsTableView) {
                    if (sortColumnId == col.id) {
                        sortAscending = !sortAscending
                    } else {
                        sortColumnId = col.id
                        sortAscending = true
                    }
                    renderHeader()
                    renderRows()
                }
            }
            headerRow.addView(tv)
        }
    }

    private fun renderRows() {
        val colId = sortColumnId ?: columns.firstOrNull()?.id
        val sorted = if (colId == null) {
            rows
        } else {
            val col = columns.first { it.id == colId }
            rows.sortedWith { a, b ->
                val cmp = compareValues(col.sortKey(a), col.sortKey(b))
                if (sortAscending) cmp else -cmp
            }
        }
        rowsContainer.removeAllViews()
        sorted.forEachIndexed { position, row ->
            rowsContainer.addView(buildRowView(row, position))
        }
    }

    private fun buildRowView(row: Row, position: Int): LinearLayout {
        val rowLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val cellBg = if (position % 2 == 0) {
            R.drawable.stats_table_row_even
        } else {
            R.drawable.stats_table_row_odd
        }
        columns.forEach { col ->
            val tv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, col.weight)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                setBackgroundResource(cellBg)
                text = row.cells[col.id].orEmpty()
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.MONOSPACE
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            if (col.id == "max") {
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                if (row.maxRate > 0.0 && row.maxRate >= globalMaxMax) {
                    tv.setTextColor(ContextCompat.getColor(context, R.color.green_400))
                }
            }
            rowLayout.addView(tv)
        }
        return rowLayout
    }
}
