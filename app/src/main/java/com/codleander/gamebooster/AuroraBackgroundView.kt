package com.codleander.gamebooster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A living background: four soft gradient orbs orbit on independent periods while
 * fine dust particles drift upward and twinkle. Everything is drawn with cached
 * radial shaders and translated by a matrix, so a frame only costs four circle
 * fills and a few dozen dots. The animation stops whenever the host activity is
 * paused, so it never burns battery in the background.
 */
class AuroraBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Blob(
        val anchorX: Float,
        val anchorY: Float,
        val radiusFactor: Float,
        val swayX: Float,
        val swayY: Float,
        val speedX: Float,
        val speedY: Float,
        val phase: Float,
        var color: Int
    )

    private class Dust(
        var x: Float,
        var y: Float,
        val velocityX: Float,
        val velocityY: Float,
        val radius: Float,
        val phase: Float,
        val speed: Float
    )

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val shaders = arrayOfNulls<RadialGradient>(BLOB_COUNT)
    private val shaderRadii = FloatArray(BLOB_COUNT)
    private val dust = ArrayList<Dust>(DUST_COUNT)

    private var startNanos = 0L
    private var accumulatedSeconds = 0f
    private var paused = false
    private var dustSeed = 20260101

    private val blobs = arrayOf(
        Blob(0.16f, 0.10f, 0.66f, 0.09f, 0.05f, 0.055f, 0.041f, 0.0f, 0),
        Blob(0.86f, 0.20f, 0.58f, 0.08f, 0.06f, 0.043f, 0.062f, 1.7f, 0),
        Blob(0.30f, 0.62f, 0.72f, 0.06f, 0.07f, 0.031f, 0.052f, 3.1f, 0),
        Blob(0.78f, 0.76f, 0.52f, 0.10f, 0.05f, 0.067f, 0.037f, 4.6f, 0)
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setPalette(
            Color.parseColor("#59D8FF"),
            Color.parseColor("#2F8BFF")
        )
    }

    fun setPalette(bright: Int, deep: Int) {
        blobs[0].color = withAlpha(bright, 70)
        blobs[1].color = withAlpha(deep, 78)
        blobs[2].color = withAlpha(bright, 44)
        blobs[3].color = withAlpha(deep, 52)
        shaders.fill(null)
        invalidate()
    }

    fun pause() {
        if (paused) return
        paused = true
        accumulatedSeconds += secondsSinceStart()
        startNanos = 0L
    }

    fun resume() {
        if (!paused) return
        paused = false
        startNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaders.fill(null)
        buildDust(w, h)
    }

    private fun buildDust(w: Int, h: Int) {
        dust.clear()
        val random = Random(dustSeed)
        for (index in 0 until DUST_COUNT) {
            dust.add(
                Dust(
                    x = random.nextFloat() * w,
                    y = random.nextFloat() * h,
                    velocityX = (random.nextFloat() - 0.5f) * 12f,
                    velocityY = -(6f + random.nextFloat() * 20f),
                    radius = (0.7f + random.nextFloat() * 1.7f) * density,
                    phase = random.nextFloat() * 6.2832f,
                    speed = 0.5f + random.nextFloat() * 1.4f
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val time = elapsedSeconds()
        drawBlobs(canvas, width, height, time)
        drawDust(canvas, width, height, time)

        if (!paused) postInvalidateOnAnimation()
    }

    private fun drawBlobs(canvas: Canvas, width: Float, height: Float, time: Float) {
        val shortest = min(width, height)
        for (index in blobs.indices) {
            val blob = blobs[index]
            val orbitX = sin(time * blob.speedX * TWO_PI + blob.phase) * blob.swayX * width
            val orbitY = cos(time * blob.speedY * TWO_PI + blob.phase * 1.37f) * blob.swayY * height
            val centerX = blob.anchorX * width + orbitX
            val centerY = blob.anchorY * height + orbitY
            val radius = blob.radiusFactor * shortest

            var shader = shaders[index]
            if (shader == null || shaderRadii[index] != radius) {
                // Cached at the origin: the per-frame matrix just moves it into place.
                shader = RadialGradient(
                    0f,
                    0f,
                    radius,
                    intArrayOf(blob.color, withAlpha(blob.color, 0)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                shaders[index] = shader
                shaderRadii[index] = radius
            }
            matrix.setTranslate(centerX, centerY)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawCircle(centerX, centerY, radius, paint)
        }
        paint.shader = null
    }

    private fun drawDust(canvas: Canvas, width: Float, height: Float, time: Float) {
        if (width <= 0f || height <= 0f) return
        dustPaint.color = Color.WHITE
        for (particle in dust) {
            val x = (particle.x + particle.velocityX * time + width) % width
            val travel = particle.y + particle.velocityY * time
            val y = ((travel % height) + height) % height
            val twinkle = abs(sin(time * particle.speed + particle.phase))
            dustPaint.alpha = (26 + 96 * twinkle).toInt()
            canvas.drawCircle(x, y, particle.radius * (0.7f + 0.5f * twinkle), dustPaint)
        }
    }

    private fun elapsedSeconds(): Float =
        accumulatedSeconds + if (startNanos == 0L) 0f else secondsSinceStart()

    private fun secondsSinceStart(): Float {
        if (startNanos == 0L) startNanos = System.nanoTime()
        return (System.nanoTime() - startNanos) / 1_000_000_000f
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val BLOB_COUNT = 4
        private const val DUST_COUNT = 44
        private const val TWO_PI = (Math.PI * 2).toFloat()
    }
}
