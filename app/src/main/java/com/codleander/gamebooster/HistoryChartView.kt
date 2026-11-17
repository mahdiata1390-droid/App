package com.codleander.gamebooster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class HistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values: List<Long> = emptyList()

    fun setData(v: List<Long>) {
        values = v.takeLast(8)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty() || width <= 0 || height <= 0) return

        val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val gap = 10f
        val barWidth = ((width - gap * (values.size + 1)) / values.size).coerceAtLeast(8f)

        paint.style = Paint.Style.FILL
        paint.color = 0xFF19B5FF.toInt()

        values.forEachIndexed { index, value ->
            val ratio = (value.toFloat() / max).coerceIn(0f, 1f)
            val left = gap + index * (barWidth + gap)
            val right = left + barWidth
            val bottom = height - 12f
            val top = bottom - (height - 28f) * ratio
            canvas.drawRoundRect(left, top, right, bottom, 10f, 10f, paint)
        }
    }
}
