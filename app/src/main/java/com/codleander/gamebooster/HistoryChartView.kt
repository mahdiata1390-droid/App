package com.codleander.gamebooster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class HistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values: List<Pair<String, Long>> = emptyList()

    init {
        labelPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        labelPaint.textSize = 10f * resources.displayMetrics.scaledDensity
    }

    fun setData(v: List<Pair<String, Long>>) {
        values = v.take(8)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty() || width <= 0 || height <= 0) return

        val maxValue = values.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
        val density = resources.displayMetrics.density
        val gap = 8f * density
        val labelHeight = 22f * density
        val availableWidth = width.toFloat() - gap * (values.size + 1)
        val barWidth = max(18f * density, availableWidth / values.size)
        val bottom = height.toFloat() - labelHeight
        val topLimit = 12f * density
        val maxBarHeight = (bottom - topLimit).coerceAtLeast(8f * density)

        barPaint.style = Paint.Style.FILL
        barPaint.color = 0xFF19B5FF.toInt()

        labelPaint.color = 0xFF7FA6C4.toInt()
        labelPaint.textAlign = Paint.Align.CENTER

        values.forEachIndexed { index, entry ->
            val ratio = (entry.second.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
            val left = gap + index * (barWidth + gap)
            val right = left + barWidth
            val top = bottom - maxBarHeight * ratio
            canvas.drawRoundRect(left, top, right, bottom, 8f * density, 8f * density, barPaint)

            val label = entry.first.take(9)
            canvas.drawText(
                label,
                (left + right) / 2f,
                height.toFloat() - 6f * density,
                labelPaint
            )
        }
    }
}
