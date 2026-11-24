package com.codleander.gamebooster

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Liquid Glass" appearance, in the spirit of the iOS 27 material: every panel becomes a
 * translucent, edge-lit sheet over a slowly moving light field, and on Android 12+ the
 * artwork behind it is genuinely blurred so the panel refracts what sits under it.
 *
 * The surfaces are tagged in the XML layouts ([TAG_SURFACE] / [TAG_BAR]) so the glass can be
 * layered on without duplicating a second set of layouts for the classic dark theme.
 */
class GlassSurfaceDrawable(
    private val radiusPx: Float,
    private val density: Float,
    private val tint: Int,
    private val strength: Float = 1f
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.15f * density
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val r = radiusPx.coerceAtMost(minOf(w, h) / 2f)

        fill.shader = LinearGradient(
            0f, 0f, w * 0.72f, h,
            intArrayOf(
                argb(0.30f * strength, Color.WHITE),
                argb(0.10f * strength, Color.WHITE),
                argb(0.04f * strength, Color.WHITE)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(0f, 0f, w, h, r, r, fill)

        // Accent light pooling in the bottom corner, like sunlight caught in the sheet.
        wash.shader = RadialGradient(
            w * 0.92f, h * 1.02f, w * 0.95f,
            intArrayOf(argb(0.22f * strength, tint), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(0f, 0f, w, h, r, r, wash)

        // Top-left specular highlight: the thin, bright lip that reads as curved glass.
        glow.shader = RadialGradient(
            w * 0.16f, -h * 0.12f, w * 0.72f,
            intArrayOf(argb(0.42f * strength, Color.WHITE), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(0f, 0f, w, h, r, r, glow)

        rect.set(edge.strokeWidth / 2f, edge.strokeWidth / 2f, w - edge.strokeWidth / 2f, h - edge.strokeWidth / 2f)
        edge.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                argb(0.55f, Color.WHITE),
                argb(0.08f, Color.WHITE),
                argb(0.30f * strength, tint),
                argb(0.70f, Color.WHITE)
            ),
            floatArrayOf(0f, 0.48f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, edge)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        wash.alpha = alpha
        edge.alpha = alpha
        glow.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = fill.alpha

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter
        wash.colorFilter = colorFilter
        edge.colorFilter = colorFilter
        glow.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        val r = radiusPx.coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f)
        outline.setRoundRect(bounds, r)
    }

    private fun argb(alpha: Float, color: Int): Int =
        Color.argb(
            (alpha.coerceIn(0f, 1f) * 255f).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
}

/**
 * The light field that gives the glass something to refract: two drifting accent pools and a
 * slow specular sweep. It redraws at low cadence and stops while the screen is paused.
 */
class LiquidGlassSheenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phaseMs = 0L
    private var lastFrameAt = 0L
    private var running = false

    var lightA: Int = 0xFF59D8FF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var lightB: Int = 0xFFA85CFF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun resume() {
        if (running) return
        running = true
        lastFrameAt = 0L
        postInvalidateOnAnimation()
    }

    fun pause() {
        running = false
        lastFrameAt = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || !running) return

        val now = SystemClock.uptimeMillis()
        if (lastFrameAt != 0L) phaseMs += (now - lastFrameAt).coerceIn(0L, 120L)
        lastFrameAt = now
        val t = (phaseMs % CYCLE_MS).toFloat() / CYCLE_MS.toFloat()
        val angle = t * TAU

        drawPool(canvas, w * (0.20f + 0.07f * sin(angle)), h * (0.20f + 0.06f * cos(angle)), w * 0.72f, lightA, 40)
        drawPool(canvas, w * (0.86f + 0.06f * cos(angle)), h * (0.44f + 0.08f * sin(angle)), w * 0.62f, lightB, 32)
        drawPool(canvas, w * (0.45f + 0.10f * sin(angle * 0.7f)), h * (0.08f + 0.04f * cos(angle * 0.7f)), w * 0.55f, Color.WHITE, 18)

        val sweep = ((t * 2f) % 1.6f - 0.3f) * w
        paint.shader = LinearGradient(
            sweep - w * 0.40f, 0f, sweep + w * 0.40f, h * 0.85f,
            intArrayOf(Color.TRANSPARENT, argb(0.10f, Color.WHITE), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        postInvalidateDelayed(FRAME_MS)
    }

    private fun drawPool(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(argb(alpha / 255f, color), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun argb(alpha: Float, color: Int): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255f).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private companion object {
        const val TAU = 6.28318f
        const val CYCLE_MS = 26_000L
        const val FRAME_MS = 48L
    }
}

object LiquidGlass {

    /** Tag used by the layouts for ordinary glass panels. */
    const val TAG_SURFACE = "glass"

    /** Larger radius for the bottom navigation bar and toolbar sheets. */
    const val TAG_BAR = "glass_bar"

    private const val TAG_SHEEN = "gb_glass_sheen"

    /**
     * Layers the glass material over the already inflated interface. Idempotent: calling it
     * again (after a backdrop change) simply rebuilds the same surfaces.
     */
    fun apply(activity: Activity) {
        if (!Prefs.glass(activity)) return
        val root = activity.findViewById<ViewGroup>(R.id.root) ?: return
        val density = activity.resources.displayMetrics.density
        val tint = Accent.bright(activity)
        decorate(root, density, tint)
        addSheen(activity, root, density, tint)
        frost(activity, density)
    }

    fun resume(activity: Activity) {
        activity.findViewById<View>(R.id.root)?.findViewWithTag<LiquidGlassSheenView>(TAG_SHEEN)?.resume()
    }

    fun pause(activity: Activity) {
        activity.findViewById<View>(R.id.root)?.findViewWithTag<LiquidGlassSheenView>(TAG_SHEEN)?.pause()
    }

    private fun decorate(view: View, density: Float, tint: Int) {
        val radius = when (view.tag) {
            TAG_BAR -> 30f * density
            TAG_SURFACE -> 22f * density
            else -> -1f
        }
        if (radius > 0f) {
            view.background = GlassSurfaceDrawable(radius, density, tint)
            view.clipToOutline = false
            view.elevation = 0f
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                decorate(view.getChildAt(index), density, tint)
            }
        }
    }

    private fun addSheen(activity: Activity, root: ViewGroup, density: Float, tint: Int) {
        if (root.findViewWithTag<View>(TAG_SHEEN) != null) {
            root.findViewWithTag<LiquidGlassSheenView>(TAG_SHEEN)?.apply {
                lightA = Accent.soft(activity)
                lightB = tint
            }
            return
        }
        val sheen = LiquidGlassSheenView(activity).apply {
            tag = TAG_SHEEN
            lightA = Accent.soft(activity)
            lightB = tint
            alpha = 0.9f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val pageHostIndex = root.indexOfChild(activity.findViewById(R.id.pageHost))
        val insertAt = if (pageHostIndex >= 0) pageHostIndex else root.childCount
        root.addView(
            sheen,
            insertAt,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        sheen.resume()
    }

    /** Real frosted blur of the artwork on Android 12+, where RenderEffect is available. */
    private fun frost(activity: Activity, density: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = 20f * density
        listOf(R.id.backdrop, R.id.aurora).forEach { id ->
            val view = activity.findViewById<View>(id) ?: return@forEach
            runCatching {
                view.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            }
        }
    }
}
