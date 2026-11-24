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

    fun fpsHud(context: Context): Boolean = prefs(context).getBoolean("fps_hud", false)

    fun setFpsHud(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("fps_hud", enabled).apply()
    }

    fun accent(context: Context): Int = prefs(context).getInt("accent", 0)

    fun setAccent(context: Context, accent: Int) {
        prefs(context).edit().putInt("accent", accent).apply()
    }

    fun soundPack(context: Context): Int = prefs(context).getInt("sound_pack", 0)

    fun setSoundPack(context: Context, pack: Int) {
        prefs(context).edit().putInt("sound_pack", pack).apply()
    }

    fun backdrop(context: Context): Int =
        prefs(context).getInt("backdrop", Backdrops.DEFAULT).coerceIn(0, Backdrops.count - 1)

    fun setBackdrop(context: Context, index: Int) {
        prefs(context).edit()
            .putInt("backdrop", index.coerceIn(0, Backdrops.count - 1))
            .apply()
    }

    fun ambient(context: Context): Boolean = prefs(context).getBoolean("ambient_audio", false)

    fun setAmbient(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("ambient_audio", enabled).apply()
    }

    fun refreshLock(context: Context): Boolean = prefs(context).getBoolean("refresh_lock", false)

    fun setRefreshLock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("refresh_lock", enabled).apply()
    }

    fun dnsEnabled(context: Context): Boolean = prefs(context).getBoolean("dns_enabled", false)

    fun setDnsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("dns_enabled", enabled).apply()
    }

    fun dnsPreset(context: Context): Int =
        prefs(context).getInt("dns_preset", PrivateDns.PRESET_OFF)

    fun setDnsPreset(context: Context, preset: Int) {
        prefs(context).edit().putInt("dns_preset", preset).apply()
    }

    fun dnsIp(context: Context): String = prefs(context).getString("dns_ip", "") ?: ""

    fun setDnsIp(context: Context, ip: String) {
        prefs(context).edit().putString("dns_ip", ip.trim()).apply()
    }

    fun dnsHost(context: Context): String = prefs(context).getString("dns_host", "") ?: ""

    fun setDnsHost(context: Context, host: String) {
        prefs(context).edit().putString("dns_host", host.trim()).apply()
    }

    /** iOS-27 style liquid glass surfaces instead of the flat dark panels. */
    fun glass(context: Context): Boolean = prefs(context).getBoolean("liquid_glass", false)

    fun setGlass(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("liquid_glass", enabled).apply()
    }

    /**
     * Opt-in only: ends a session automatically once the platform reports severe heat.
     * Off by default, and the battery-temperature trigger was removed on request, so an
     * enabled session is never ended just because the phone reached 45 °C.
     */
    fun thermalGuard(context: Context): Boolean =
        prefs(context).getBoolean("thermal_guard_optin", false)

    fun setThermalGuard(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("thermal_guard_optin", enabled).apply()
    }

    /** Minutes between in-session break reminders; 0 turns them off. */
    fun breakMinutes(context: Context): Int = prefs(context).getInt("break_minutes", 60)

    fun setBreakMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt("break_minutes", minutes.coerceIn(0, 240)).apply()
    }

    fun displayName(context: Context): String =
        prefs(context).getString("display_name", "")?.trim() ?: ""

    fun setDisplayName(context: Context, name: String) {
        prefs(context).edit().putString("display_name", name.trim()).apply()
    }
}
