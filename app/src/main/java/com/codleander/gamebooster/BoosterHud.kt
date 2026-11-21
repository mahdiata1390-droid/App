package com.codleander.gamebooster

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The in-game overlay: a draggable bubble, a draggable toolbox panel and an optional compact
 * FPS pill. Dragging only ever moves a view; nothing is deleted by a gesture. Closing is done
 * with the dedicated close button, and ending the session with the red END button.
 */
class BoosterHud(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onEndSession()
        fun onClearRam()
        fun onOpenApp()
        fun onDndChanged()
    }

    private val wm: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val prefs = context.getSharedPreferences("booster_settings", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density

    private var bubble: FrameLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var pill: TextView? = null

    private var bubbleGlyph: TextView? = null
    private var timerText: TextView? = null
    private var fpsChip: TextView? = null
    private var ramChip: TextView? = null
    private var tempChip: TextView? = null
    private var dndButton: TextView? = null
    private var fpsButton: TextView? = null

    private var fpsEnabled = Prefs.fpsHud(context)
    private var live = true

    fun show() {
        if (wm == null || !Settings.canDrawOverlays(context)) return
        if (bubble == null) addBubble()
        if (fpsEnabled) showPill()
    }

    fun removeAll() {
        hidePanel()
        hidePill()
        bubble?.let { view -> runCatching { wm?.removeView(view) } }
        bubble = null
        bubbleGlyph = null
        bubbleLp = null
    }

    fun setFpsVisible(enabled: Boolean) {
        fpsEnabled = enabled
        if (enabled) showPill() else hidePill()
        refreshFpsLabel()
    }

    fun setLive(inGame: Boolean) {
        live = inGame
        bubbleGlyph?.alpha = if (inGame) 1f else 0.55f
    }

    fun update(durationMs: Long, fps: Float, ramPercent: Int, tempC: Float?) {
        val seconds = (durationMs / 1000L).coerceAtLeast(0L)
        timerText?.text = String.format(
            Locale.US, "%02d:%02d:%02d",
            seconds / 3600, (seconds % 3600) / 60, seconds % 60
        )
        val fpsValue = fps.roundToInt()
        val tempValue = tempC?.let { String.format(Locale.US, "%.0f°C", it) } ?: "--°C"
        fpsChip?.text = String.format(Locale.US, "%d FPS", fpsValue)
        ramChip?.text = String.format(Locale.US, "%d%% RAM", ramPercent)
        tempChip?.text = tempValue
        pill?.text = String.format(Locale.US, "%d FPS · %d%% · %s", fpsValue, ramPercent, tempValue)
    }

    // ------------------------------------------------------------------------ bubble

    private fun addBubble() {
        val manager = wm ?: return
        val size = dp(58)
        val glyph = TextView(context).apply {
            text = "⚡"
            setTextSize(24f)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val view = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1E6BFF.toInt(), 0xFF0B2340.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dpF(2f).toInt(), 0xFF59D8FF.toInt())
            }
            elevation = dpF(10f)
            contentDescription = context.getString(R.string.hud_bubble_desc)
            addView(glyph)
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("hud_bubble_x", dp(16))
            y = prefs.getInt("hud_bubble_y", dp(170))
        }
        makeDraggable(view, view, lp, "hud_bubble") { togglePanel() }
        bubble = view
        bubbleGlyph = glyph
        bubbleLp = lp
        val added = runCatching { manager.addView(view, lp) }.isSuccess
        if (!added) {
            bubble = null
            bubbleGlyph = null
        }
        setLive(live)
    }

    // ------------------------------------------------------------------------- panel

    private fun togglePanel() {
        if (panel == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        val manager = wm ?: return
        if (panel != null) return

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF20C2138.toInt(), 0xF2030A14.toInt())
            ).apply {
                cornerRadius = dpF(22f)
                setStroke(dpF(1.2f).toInt().coerceAtLeast(1), 0x5559D8FF.toInt())
            }
            elevation = dpF(18f)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val title = text("⚡ " + context.getString(R.string.hud_title), 12f, 0xFF59D8FF.toInt(), true)
            .apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        val grip = text("⋮⋮", 13f, 0xFF7EA3C4.toInt(), true).apply {
            setPadding(dp(6), 0, dp(10), 0)
            contentDescription = context.getString(R.string.hud_drag_hint)
        }
        val close = text("✕", 14f, 0xFFEAF6FF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = fill(0x1AFFFFFF, 10f)
            contentDescription = context.getString(R.string.hud_close)
            setOnClickListener { hidePanel() }
        }
        header.addView(title)
        header.addView(grip)
        header.addView(close)

        timerText = text("00:00:00", 20f, 0xFFEAF6FF.toInt(), true).apply {
            letterSpacing = 0.06f
        }

        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }
        fpsChip = chip("-- FPS", 0xFF59D8FF.toInt())
        ramChip = chip("--% RAM", 0xFF8FD6FF.toInt())
        tempChip = chip("--°C", 0xFFFFD35C.toInt())
        chips.addView(fpsChip)
        chips.addView(ramChip)
        chips.addView(tempChip)

        dndButton = button(dndLabel(), 0x1F59D8FF.toInt(), 0xFF59D8FF.toInt()).apply {
            text = dndLabel()
            setOnClickListener {
                val result = DndHelper.toggle(context)
                if (result == null) {
                    DndHelper.openAccessSettings(context)
                    toast(context.getString(R.string.dnd_access_needed))
                } else {
                    listener.onDndChanged()
                    text = dndLabel()
                    toast(
                        context.getString(
                            if (result) R.string.dnd_on_toast else R.string.dnd_off_toast
                        )
                    )
                }
            }
        }
        val ramButton = button(
            context.getString(R.string.hud_free_ram), 0x1F3BE38A, 0xFF3BE38A.toInt()
        ).apply {
            setOnClickListener {
                toast(context.getString(R.string.ram_cleaning))
                listener.onClearRam()
            }
        }
        val dndRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        dndRow.addView(dndButton)
        dndRow.addView(ramButton)

        fpsButton = button(
            context.getString(R.string.hud_fps_hud), 0x14FFFFFF, 0xFFEAF6FF.toInt()
        ).apply {
            setOnClickListener {
                fpsEnabled = !fpsEnabled
                Prefs.setFpsHud(context, fpsEnabled)
                if (fpsEnabled) showPill() else hidePill()
                refreshFpsLabel()
                toast(
                    context.getString(
                        if (fpsEnabled) R.string.fps_hud_enabled else R.string.fps_hud_disabled
                    )
                )
            }
        }
        val appButton = button(
            context.getString(R.string.hud_open_app), 0x14FFFFFF, 0xFFEAF6FF.toInt()
        ).apply {
            setOnClickListener {
                hidePanel()
                listener.onOpenApp()
            }
        }
        val secondRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        secondRow.addView(fpsButton)
        secondRow.addView(appButton)

        val end = text(context.getString(R.string.hud_end_session), 12f, 0xFFFF5C7A.toInt(), true)
            .apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(11), dp(8), dp(11))
                background = fill(0x33FF5C7A, 14f, 0x66FF5C7A, 1f)
                setOnClickListener {
                    hidePanel()
                    listener.onEndSession()
                }
            }

        val endWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        endWrap.addView(
            end,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(header)
        card.addView(timerText)
        card.addView(chips)
        card.addView(dndRow)
        card.addView(secondRow)
        card.addView(endWrap)

        val lp = WindowManager.LayoutParams(
            dp(252),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("hud_panel_x", dp(12))
            y = prefs.getInt("hud_panel_y", dp(200))
        }
        lp.x = lp.x.coerceIn(0, (context.resources.displayMetrics.widthPixels - dp(252)).coerceAtLeast(0))
        makeDraggable(header, card, lp, "hud_panel", null)
        makeDraggable(title, card, lp, "hud_panel", null)

        panel = card
        refreshFpsLabel()
        dndButton?.text = dndLabel()
        if (!runCatching { manager.addView(card, lp) }.isSuccess) {
            panel = null
        }
    }

    private fun hidePanel() {
        panel?.let { view -> runCatching { wm?.removeView(view) } }
        panel = null
        timerText = null
        fpsChip = null
        ramChip = null
        tempChip = null
        dndButton = null
        fpsButton = null
    }

    // --------------------------------------------------------------------------- pill

    private fun showPill() {
        val manager = wm ?: return
        if (pill != null || !Settings.canDrawOverlays(context)) return
        val view = text("-- FPS", 12f, 0xFF59D8FF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = fill(0xE6040A14.toInt(), 14f, 0x6659D8FF, 1f)
            elevation = dpF(10f)
            contentDescription = context.getString(R.string.hud_fps_hud)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("hud_pill_x", dp(16))
            y = prefs.getInt("hud_pill_y", dp(120))
        }
        makeDraggable(view, view, lp, "hud_pill") { togglePanel() }
        pill = view
        if (!runCatching { manager.addView(view, lp) }.isSuccess) {
            pill = null
        }
    }

    private fun hidePill() {
        pill?.let { view -> runCatching { wm?.removeView(view) } }
        pill = null
    }

    // ------------------------------------------------------------------------ helpers

    private fun dndLabel(): String = when {
        !DndHelper.hasAccess(context) -> context.getString(R.string.dnd_need_access)
        DndHelper.isOn(context) -> context.getString(R.string.dnd_on)
        else -> context.getString(R.string.dnd_off)
    }

    private fun refreshFpsLabel() {
        fpsButton?.text = context.getString(
            if (fpsEnabled) R.string.hud_fps_visible else R.string.hud_fps_hidden
        )
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(context).apply {
            text = value
            setTextSize(sizeSp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun chip(value: String, color: Int): TextView =
        TextView(context).apply {
            text = value
            setTextSize(11f)
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = fill(0x14FFFFFF, 9f)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(5) }
        }

    private fun button(value: String, bgColor: Int, textColor: Int): TextView =
        TextView(context).apply {
            text = value
            setTextSize(10f)
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(11), dp(4), dp(11))
            background = fill(bgColor, 12f)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(6) }
            isClickable = true
        }

    private fun fill(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 1f) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(color)
            strokeColor?.let { setStroke(dpF(strokeDp).toInt().coerceAtLeast(1), it) }
        }

    private fun makeDraggable(
        view: View,
        target: View,
        lp: WindowManager.LayoutParams,
        key: String,
        onTap: (() -> Unit)?
    ) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = lp.x
                        startY = lp.y
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downRawX).roundToInt()
                        val dy = (event.rawY - downRawY).roundToInt()
                        if (!dragging && (abs(dx) > dp(6) || abs(dy) > dp(6))) dragging = true
                        if (dragging) {
                            lp.x = (startX + dx).coerceIn(0, maxX(target))
                            lp.y = (startY + dy).coerceIn(0, maxY(target))
                            runCatching { wm?.updateViewLayout(target, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            prefs.edit().putInt(key + "_x", lp.x).putInt(key + "_y", lp.y).apply()
                        } else {
                            onTap?.invoke()
                        }
                        dragging = false
                    }
                    MotionEvent.ACTION_CANCEL -> dragging = false
                }
                return true
            }
        })
    }

    private fun maxX(view: View): Int {
        val width = if (view.width > 0) view.width else dp(252)
        return (context.resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
    }

    private fun maxY(view: View): Int {
        val height = if (view.height > 0) view.height else dp(280)
        return (context.resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun dpF(value: Float): Float = value * density

    private fun toast(message: String) {
        runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}
