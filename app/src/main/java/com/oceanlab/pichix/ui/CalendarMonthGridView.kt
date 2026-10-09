package com.oceanlab.pichix.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.oceanlab.pichix.R
import kotlin.math.min

/**
 * Grilla mensual 7×N con cabecera Lu–Do y celdas cuadradas.
 */
class CalendarMonthGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class DayCell(
        val day: Int,
        val earned: Double,
        val goal: Double = 0.0,
    )

    /** null = casilla vacía (offset o relleno). */
    var cells: List<DayCell?> = emptyList()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var selectedDay: Int = 1
        set(value) {
            field = value
            invalidate()
        }

    var maxEarnedInMonth: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }

    var onDayClick: ((Int) -> Unit)? = null

    private var cellSizePx = 0
    private val cellRect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val earnedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val weekdayLabels = arrayOf("Lu", "Ma", "Mi", "Ju", "Vi", "Sa", "Do")

    private fun headerHeightPx(): Int =
        if (cellSizePx > 0) (cellSizePx * 0.32f).toInt().coerceIn(16, 26) else 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellSizePx = if (width > 0) width / COLS else 0
        val rows = if (cells.isEmpty()) 6 else (cells.size + COLS - 1) / COLS
        val height = if (cellSizePx > 0) headerHeightPx() + cellSizePx * rows else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (cellSizePx <= 0) return

        val headerH = headerHeightPx()
        val density = resources.displayMetrics.density
        val scaledDensity = resources.displayMetrics.scaledDensity

        headerPaint.textSize = when {
            cellSizePx >= 52 -> 10f * scaledDensity
            cellSizePx >= 44 -> 9f * scaledDensity
            else -> 8f * scaledDensity
        }
        headerPaint.color = ContextCompat.getColor(context, R.color.text_hint)
        weekdayLabels.forEachIndexed { col, label ->
            val cx = col * cellSizePx + cellSizePx / 2f
            val cy = headerH / 2f - (headerPaint.descent() + headerPaint.ascent()) / 2f
            canvas.drawText(label, cx, cy, headerPaint)
        }

        if (cells.isEmpty()) return

        val pad = 2f * density
        val corner = min(cellSizePx * 0.14f, 8f * density)

        dayPaint.textSize = when {
            cellSizePx >= 52 -> 11f * scaledDensity
            cellSizePx >= 44 -> 10f * scaledDensity
            else -> 9f * scaledDensity
        }
        earnedPaint.textSize = when {
            cellSizePx >= 52 -> 9f * scaledDensity
            cellSizePx >= 44 -> 8f * scaledDensity
            else -> 7f * scaledDensity
        }

        cells.forEachIndexed { index, cell ->
            if (cell == null) return@forEachIndexed
            val col = index % COLS
            val row = index / COLS
            val left = col * cellSizePx.toFloat() + pad
            val top = headerH + row * cellSizePx.toFloat() + pad
            val right = (col + 1) * cellSizePx.toFloat() - pad
            val bottom = headerH + (row + 1) * cellSizePx.toFloat() - pad
            cellRect.set(left, top, right, bottom)

            val selected = cell.day == selectedDay
            val goal = cell.goal
            val level = CalendarUi.goalLevel(cell.earned, goal)

            val (fillColor, earnedColor) = cellColors(cell.earned, level)
            fillPaint.color = fillColor
            canvas.drawRoundRect(cellRect, corner, corner, fillPaint)

            strokePaint.strokeWidth = if (selected) 2f * density else 1f * density
            strokePaint.color = strokeColor(selected, level)
            canvas.drawRoundRect(cellRect, corner, corner, strokePaint)

            val cx = cellRect.centerX()
            val dayY = cellRect.top + cellRect.height() * 0.38f - (dayPaint.descent() + dayPaint.ascent()) / 2f
            dayPaint.color = if (selected) {
                ContextCompat.getColor(context, R.color.accent_teal_dark)
            } else {
                ContextCompat.getColor(context, R.color.text_primary)
            }
            canvas.drawText(cell.day.toString(), cx, dayY, dayPaint)

            earnedPaint.color = earnedColor
            val earnedY = cellRect.top + cellRect.height() * 0.72f -
                (earnedPaint.descent() + earnedPaint.ascent()) / 2f
            canvas.drawText(CalendarUi.formatCellEarned(cell.earned), cx, earnedY, earnedPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cellSizePx <= 0 || cells.isEmpty()) return false
        val headerH = headerHeightPx()
        when (event.action) {
            MotionEvent.ACTION_UP -> {
                if (event.y < headerH) return false
                val col = (event.x / cellSizePx).toInt().coerceIn(0, COLS - 1)
                val row = ((event.y - headerH) / cellSizePx).toInt().coerceAtLeast(0)
                val index = row * COLS + col
                cells.getOrNull(index)?.let { day ->
                    onDayClick?.invoke(day.day)
                    return true
                }
            }
        }
        return true
    }

    private fun cellColors(earned: Double, level: CalendarUi.GoalLevel?): Pair<Int, Int> {
        val ctx = context
        return when (level) {
            CalendarUi.GoalLevel.MET -> Pair(
                ContextCompat.getColor(ctx, R.color.green_bg),
                ContextCompat.getColor(ctx, R.color.badge_green_text),
            )
            CalendarUi.GoalLevel.HALF -> Pair(
                ContextCompat.getColor(ctx, R.color.amber_bg),
                ContextCompat.getColor(ctx, R.color.badge_amber_text),
            )
            CalendarUi.GoalLevel.LOW -> Pair(
                ContextCompat.getColor(ctx, R.color.coral_bg),
                ContextCompat.getColor(ctx, R.color.badge_coral_text),
            )
            null -> if (CalendarUi.hasEarned(earned)) {
                val intensity = if (maxEarnedInMonth > 0) earned / maxEarnedInMonth else 0.0
                val alpha = (40 + (intensity * 60).toInt()).coerceIn(40, 100)
                val base = ContextCompat.getColor(ctx, R.color.accent_teal_bg)
                Pair(
                    android.graphics.Color.argb(
                        alpha,
                        android.graphics.Color.red(base),
                        android.graphics.Color.green(base),
                        android.graphics.Color.blue(base),
                    ),
                    ContextCompat.getColor(ctx, R.color.accent_teal_dark),
                )
            } else {
                Pair(
                    ContextCompat.getColor(ctx, R.color.bg_stat_chip),
                    ContextCompat.getColor(ctx, R.color.text_hint),
                )
            }
        }
    }

    private fun strokeColor(selected: Boolean, level: CalendarUi.GoalLevel?): Int {
        val ctx = context
        return when {
            selected -> ContextCompat.getColor(ctx, R.color.accent_teal)
            level == CalendarUi.GoalLevel.MET -> ContextCompat.getColor(ctx, R.color.green_400)
            level == CalendarUi.GoalLevel.HALF -> ContextCompat.getColor(ctx, R.color.amber_400)
            level == CalendarUi.GoalLevel.LOW -> ContextCompat.getColor(ctx, R.color.coral_600)
            else -> ContextCompat.getColor(ctx, R.color.border_subtle)
        }
    }

    private companion object {
        const val COLS = 7
    }
}
