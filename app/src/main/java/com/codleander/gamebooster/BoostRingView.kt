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
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class BoostRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val accentBase = Accent.base(context)
    private val accentBright = Accent.bright(context)

    private var progress: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var animator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null
    private var pulse = 0f
    private var spin = 0f
    private var active = false

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
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.gb_ring_glow)
    }
    private val scannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accentBright
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
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

    /** Keeps a soft breathing glow and a slow scanner arc while a boost is running. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) startAmbient() else stopAmbient()
        invalidate()
    }

    private fun startAmbient() {
        if (pulseAnimator == null) {
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    if (active) invalidate()
                }
            }
        }
        if (spinAnimator == null) {
            spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 5200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    spin = it.animatedValue as Float
                    if (active) invalidate()
                }
            }
        }
        if (pulseAnimator?.isRunning != true) pulseAnimator?.start()
        if (spinAnimator?.isRunning != true) spinAnimator?.start()
    }

    private fun stopAmbient() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        spinAnimator?.cancel()
        spinAnimator = null
        pulse = 0f
        spin = 0f
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        stopAmbient()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val gradient = SweepGradient(
            cx, cy,
            intArrayOf(
                accentBase,
                accentBright,
                accentBase
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

        // Outer tick ring: lit ticks follow the boost progress.
        val tickOuter = radius + 18f * density
        val tickInner = tickOuter - 5f * density
        val tickCount = 64
        for (index in 0 until tickCount) {
            val angle = Math.toRadians(index * (360.0 / tickCount) - 90.0)
            val lit = index / tickCount.toFloat() <= progress
            tickPaint.color = if (lit) {
                (accentBase and 0x00FFFFFF) or (0xCC shl 24)
            } else {
                (accentBase and 0x00FFFFFF) or (0x29 shl 24)
            }
            tickPaint.strokeWidth = (if (lit) 1.8f else 1.1f) * density
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            canvas.drawLine(
                cx + c * tickInner, cy + s * tickInner,
                cx + c * tickOuter, cy + s * tickOuter,
                tickPaint
            )
        }

        dashPaint.strokeWidth = 1.2f * density
        canvas.drawCircle(cx, cy, radius + 10f * density, dashPaint)

        trackPaint.strokeWidth = stroke
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        if (active) {
            ambientPaint.strokeWidth = stroke * 3.6f
            ambientPaint.alpha = (30 + 60 * pulse).toInt()
            canvas.drawArc(rect, 0f, 360f, false, ambientPaint)

            val scannerRect = RectF(
                cx - radius - 5f * density, cy - radius - 5f * density,
                cx + radius + 5f * density, cy + radius + 5f * density
            )
            scannerPaint.strokeWidth = 2.4f * density
            scannerPaint.alpha = 150
            canvas.drawArc(scannerRect, spin, 46f, false, scannerPaint)
        }

        if (progress > 0.001f) {
            glowPaint.strokeWidth = stroke * 2.6f
            glowPaint.alpha = 255
            canvas.drawArc(rect, -90f, 360f * progress, false, glowPaint)

            arcPaint.strokeWidth = stroke
            arcPaint.shader = shader
            canvas.drawArc(rect, -90f, 360f * progress, false, arcPaint)
        }
    }
}
