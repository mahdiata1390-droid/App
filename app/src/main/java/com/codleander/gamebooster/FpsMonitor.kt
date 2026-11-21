package com.codleander.gamebooster

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

/**
 * Samples the display frame cadence through Choreographer and reports a smoothed FPS value
 * roughly twice per second. Without root this is the only frame rate an app can observe:
 * it reflects how many frames the compositor actually presented while the overlay is visible.
 */
class FpsMonitor(private val onSample: (Float) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private val choreographer: Choreographer? =
        runCatching { Choreographer.getInstance() }.getOrNull()

    private var running = false
    private var frames = 0
    private var windowStartNs = 0L
    private var smoothed = 0f

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (windowStartNs == 0L) {
                windowStartNs = frameTimeNanos
                frames = 0
            }
            frames++
            val elapsedNs = frameTimeNanos - windowStartNs
            if (elapsedNs >= WINDOW_NS) {
                val fps = frames * 1_000_000_000f / elapsedNs
                smoothed = if (smoothed <= 0f) fps else smoothed * 0.45f + fps * 0.55f
                onSample(smoothed.coerceIn(1f, 240f))
                windowStartNs = frameTimeNanos
                frames = 0
            }
            choreographer?.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        windowStartNs = 0L
        frames = 0
        smoothed = 0f
        handler.post { choreographer?.postFrameCallback(callback) }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { choreographer?.removeFrameCallback(callback) }
    }

    private companion object {
        const val WINDOW_NS = 450_000_000L
    }
}
