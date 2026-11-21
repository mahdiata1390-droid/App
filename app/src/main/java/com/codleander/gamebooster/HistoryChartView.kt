package com.codleander.gamebooster

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.max

class HistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var values: FloatArray = FloatArray(0)
    private var labels: List<String> = emptyList()
    private var reveal = 1f
    private var revealAnimator: ValueAnimator? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2A2F8BFF
        strokeWidth = 1f * density
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7EA3C4.toInt()
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF8FE3FF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }

    fun setData(values: FloatArray, labels: List<String>) {
        this.values = values
        this.labels = labels
        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty() || width <= 0 || height <= 0) return

        val left = 36f * density
        val right = width - 10f * density
        val top = 16f * density
        val bottom = height - 20f * density
        val plotWidth = (right - left).coerceAtLeast(1f)
        val plotHeight = (bottom - top).coerceAtLeast(1f)

        val maxValue = max(values.maxOrNull() ?: 0f, 1f)

        for (i in 0..4) {
            val y = bottom - plotHeight * i / 4f
            gridPaint.color = if (i == 0) 0x552F8BFF else 0x2A2F8BFF
            canvas.drawLine(left, y, right, y, gridPaint)
            axisPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(formatValue(maxValue * i / 4f), left - 8f * density, y + 3f * density, axisPaint)
        }

        val stepX = plotWidth / (values.size - 1).coerceAtLeast(1)
        val points = values.mapIndexed { index, value ->
            PointF(
                left + stepX * index,
                bottom - plotHeight * ((value * reveal) / maxValue).coerceIn(0f, 1f)
            )
        }

        val line = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val previous = points[i - 1]
                val current = points[i]
                val midX = (previous.x + current.x) / 2f
                cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
            }
        }

        val area = Path(line).apply {
            lineTo(points.last().x, bottom)
            lineTo(points.first().x, bottom)
            close()
        }
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x802F8BFF.toInt(), 0x0A2F8BFF),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(area, fillPaint)

        glowPaint.set(linePaint)
        glowPaint.strokeWidth = 9f * density
        glowPaint.alpha = 55
        canvas.drawPath(line, glowPaint)
        canvas.drawPath(line, linePaint)

        if (reveal > 0.98f) {
            dotPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(points.last().x, points.last().y, 3f * density, dotPaint)
        }

        axisPaint.textAlign = Paint.Align.CENTER
        val every = (values.size / 6).coerceAtLeast(1)
        labels.forEachIndexed { index, label ->
            if (index % every == 0 || index == labels.lastIndex) {
                val x = points.getOrNull(index)?.x ?: return@forEachIndexed
                canvas.drawText(label, x.coerceIn(left, right), height - 4f * density, axisPaint)
            }
        }
    }

    private fun formatValue(minutes: Float): String =
        if (minutes >= 60f) String.format(Locale.US, "%.1fh", minutes / 60f)
        else String.format(Locale.US, "%dm", minutes.toInt())
}
