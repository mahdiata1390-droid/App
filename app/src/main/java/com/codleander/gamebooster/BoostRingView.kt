package com.codleander.gamebooster

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min

class BoostRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private var progress: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.gb_ring_track)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.gb_ring_glow)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.gb_ring_dash)
        pathEffect = DashPathEffect(floatArrayOf(2f * density, 7f * density), 0f)
    }

    private var shader: SweepGradient? = null

    fun animateTo(target: Float, duration: Long = 900L) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target.coerceIn(0f, 1f)).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    fun setProgressNow(value: Float) {
        animator?.cancel()
        progress = value
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val gradient = SweepGradient(
            cx, cy,
            intArrayOf(
                ContextCompat.getColor(context, R.color.gb_accent),
                ContextCompat.getColor(context, R.color.gb_accent_bright),
                ContextCompat.getColor(context, R.color.gb_accent)
            ),
            floatArrayOf(0f, 0.45f, 1f)
        )
        val matrix = Matrix().apply { postRotate(-90f, cx, cy) }
        gradient.setLocalMatrix(matrix)
        shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 15f * density
        if (radius <= 0f) return
        val stroke = 9f * density
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        dashPaint.strokeWidth = 1.2f * density
        canvas.drawCircle(cx, cy, radius + 10f * density, dashPaint)

        trackPaint.strokeWidth = stroke
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        if (progress > 0.001f) {
            glowPaint.strokeWidth = stroke * 2.6f
            canvas.drawArc(rect, -90f, 360f * progress, false, glowPaint)

            arcPaint.strokeWidth = stroke
            arcPaint.shader = shader
            canvas.drawArc(rect, -90f, 360f * progress, false, arcPaint)
        }
    }
}
