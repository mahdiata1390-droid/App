package com.codleander.gamebooster

import android.content.Context

object Prefs {
    const val MODE_BATTERY = 0
    const val MODE_BALANCED = 1
    const val MODE_PERFORMANCE = 2

    private const val FILE = "booster_settings"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun boostMode(context: Context): Int = prefs(context).getInt("boost_mode", MODE_BALANCED)

    fun setBoostMode(context: Context, mode: Int) {
        prefs(context).edit().putInt("boost_mode", mode.coerceIn(MODE_BATTERY, MODE_PERFORMANCE)).apply()
    }

    fun autoBoost(context: Context): Boolean = prefs(context).getBoolean("auto_boost", true)

    fun setAutoBoost(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_boost", enabled).apply()
    }

    fun gameMode(context: Context): Boolean = prefs(context).getBoolean("game_mode", true)

    fun setGameMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("game_mode", enabled).apply()
    }

    fun notifications(context: Context): Boolean = prefs(context).getBoolean("notify_boosts", true)

    fun setNotifications(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("notify_boosts", enabled).apply()
    }

    fun amoled(context: Context): Boolean = prefs(context).getBoolean("amoled", false)

    fun setAmoled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("amoled", enabled).apply()
    }

    fun language(context: Context): String = prefs(context).getString("language", "en") ?: "en"

    fun setLanguage(context: Context, language: String) {
        prefs(context).edit().putString("language", language).apply()
    }

    fun displayName(context: Context): String =
        prefs(context).getString("display_name", "")?.trim() ?: ""

    fun setDisplayName(context: Context, name: String) {
        prefs(context).edit().putString("display_name", name.trim()).apply()
    }
}
