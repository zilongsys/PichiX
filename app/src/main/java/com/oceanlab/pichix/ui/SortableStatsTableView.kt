package com.oceanlab.pichix.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.oceanlab.pichix.R

/**
 * Tabla compacta con cabeceras clicables para ordenar filas.
 */
class SortableStatsTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Column(
        val id: String,
        val title: String,
        val widthDp: Int = 56,
        val sortKey: (Row) -> Comparable<*> = { row -> row.cells[id].orEmpty() },
    )

    data class Row(
        val cells: Map<String, String>,
        val maxRate: Double = 0.0,
    )

    private val headerRow: LinearLayout
    private val rvRows: RecyclerView
    private var columns: List<Column> = emptyList()
    private var rows: List<Row> = emptyList()
    private var sortColumnId: String? = null
    private var sortAscending = true
    private var globalMaxMax = 0.0

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.widget_sortable_stats_table, this, true)
        headerRow = findViewById(R.id.statsTableHeaderRow)
        rvRows = findViewById(R.id.rvStatsTableRows)
        rvRows.layoutManager = LinearLayoutManager(context)
        rvRows.adapter = TableAdapter()
    }

    fun bind(cols: List<Column>, data: List<Row>, highlightMaxRate: Double) {
        columns = cols
        rows = data
        globalMaxMax = highlightMaxRate
        if (sortColumnId == null && cols.isNotEmpty()) {
            sortColumnId = cols.first().id
        }
        renderHeader()
        renderRows()
    }

    private fun renderHeader() {
        headerRow.removeAllViews()
        val density = resources.displayMetrics.density
        columns.forEach { col ->
            val tv = LayoutInflater.from(context)
                .inflate(R.layout.item_stats_table_cell, headerRow, false) as TextView
            tv.layoutParams = LinearLayout.LayoutParams(
                (col.widthDp * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val active = col.id == sortColumnId
            tv.text = if (active) {
                "${col.title}${if (sortAscending) " ▲" else " ▼"}"
            } else {
                col.title
            }
            tv.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.tab_active_bg else R.color.accent_teal,
                ),
            )
            tv.setOnClickListener {
                if (sortColumnId == col.id) {
                    sortAscending = !sortAscending
                } else {
                    sortColumnId = col.id
                    sortAscending = true
                }
                renderHeader()
                renderRows()
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
            val list = rows.sortedWith { a, b ->
                val va = col.sortKey(a)
                val vb = col.sortKey(b)
                val cmp = compareValues(va, vb)
                if (sortAscending) cmp else -cmp
            }
            list
        }
        (rvRows.adapter as TableAdapter).submit(sorted)
    }

    private inner class TableAdapter : RecyclerView.Adapter<TableAdapter.VH>() {
        private val items = mutableListOf<Row>()

        fun submit(list: List<Row>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val rowLayout: LinearLayout) : RecyclerView.ViewHolder(rowLayout) {
            fun bind(row: Row) {
                rowLayout.removeAllViews()
                val density = resources.displayMetrics.density
                columns.forEach { col ->
                    val tv = LayoutInflater.from(context)
                        .inflate(R.layout.item_stats_table_cell, rowLayout, false) as TextView
                    tv.layoutParams = LinearLayout.LayoutParams(
                        (col.widthDp * density).toInt(),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    tv.text = row.cells[col.id].orEmpty()
                    tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    tv.textSize = 9f
                    tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    if (col.id == "max" && row.maxRate > 0.0 && row.maxRate >= globalMaxMax) {
                        tv.setTextColor(ContextCompat.getColor(context, R.color.green_400))
                        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    } else if (col.id == "max") {
                        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    }
                    rowLayout.addView(tv)
                }
            }
        }
    }
}
