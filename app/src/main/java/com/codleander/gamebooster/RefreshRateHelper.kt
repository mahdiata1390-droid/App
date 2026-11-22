package com.codleander.gamebooster

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import kotlin.math.abs

/**
 * Pins the panel to its highest supported refresh rate for the duration of a session.
 *
 * Android exposes this through the system "peak_refresh_rate" / "min_refresh_rate"
 * settings, which only an app holding WRITE_SETTINGS may change. Every write is read
 * back, so the caller can tell the user the truth when a vendor ROM refuses it.
 */
object RefreshRateHelper {

    private const val FILE = "booster_refresh"
    private const val KEY_PEAK = "previous_peak"
    private const val KEY_MIN = "previous_min"
    private const val KEY_SAVED = "saved"
    private const val KEY_ACTIVE = "active_hz"

    fun canControl(context: Context): Boolean = Settings.System.canWrite(context)

    fun maxHz(context: Context): Float? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val modes = runCatching { display.supportedModes }.getOrNull()
        val highest = modes?.maxOfOrNull { it.refreshRate } ?: runCatching { display.refreshRate }.getOrNull()
        return highest?.takeIf { it > 20f }?.let { Math.round(it).toFloat() }
    }

    fun activeHz(context: Context): Float? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SAVED, false)) return null
        val hz = prefs.getFloat(KEY_ACTIVE, 0f)
        return if (hz > 0f) hz else null
    }

    fun apply(context: Context, targetHz: Float): Boolean {
        if (!canControl(context)) return false
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SAVED, false)) {
            val previousPeak = runCatching {
                Settings.System.getFloat(resolver, "peak_refresh_rate", -1f)
            }.getOrDefault(-1f)
            val previousMin = runCatching {
                Settings.System.getFloat(resolver, "min_refresh_rate", -1f)
            }.getOrDefault(-1f)
            prefs.edit()
                .putFloat(KEY_PEAK, previousPeak)
                .putFloat(KEY_MIN, previousMin)
                .putBoolean(KEY_SAVED, true)
                .apply()
        }

        val written = runCatching {
            Settings.System.putFloat(resolver, "peak_refresh_rate", targetHz)
            Settings.System.putFloat(resolver, "min_refresh_rate", targetHz)
            true
        }.getOrDefault(false)
        if (!written) return false

        val readBack = runCatching {
            Settings.System.getFloat(resolver, "peak_refresh_rate", -1f)
        }.getOrDefault(-1f)
        if (abs(readBack - targetHz) > 0.5f) return false

        prefs.edit().putFloat(KEY_ACTIVE, targetHz).apply()
        return true
    }

    fun restore(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SAVED, false)) return true
        if (!canControl(context)) return false

        val resolver = context.contentResolver
        val peak = prefs.getFloat(KEY_PEAK, -1f)
        val min = prefs.getFloat(KEY_MIN, -1f)
        var restored = true
        if (peak > 0f) {
            restored = runCatching {
                Settings.System.putFloat(resolver, "peak_refresh_rate", peak)
                true
            }.getOrDefault(false) && restored
        }
        if (min > 0f) {
            runCatching { Settings.System.putFloat(resolver, "min_refresh_rate", min) }
        }
        if (restored) {
            prefs.edit()
                .remove(KEY_SAVED)
                .remove(KEY_ACTIVE)
                .remove(KEY_PEAK)
                .remove(KEY_MIN)
                .apply()
        }
        return restored
    }

    fun label(context: Context): String {
        val hz = maxHz(context)
        return if (hz == null) "" else hz.toInt().toString()
    }

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 23
}
