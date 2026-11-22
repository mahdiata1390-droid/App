package com.codleander.gamebooster

import android.content.Context
import org.json.JSONObject

data class GameProfile(
    val dnd: Boolean,
    val brightnessLock: Boolean,
    val refreshLock: Boolean,
    val hud: Boolean,
    val mode: Int,
    val custom: Boolean
)

/**
 * Per-game rules: every game can keep its own interruption, brightness, refresh rate,
 * toolbox and boost-mode choices. Games without a custom profile inherit the global
 * settings so nothing changes for users who never open this screen.
 */
object GameProfiles {

    private const val FILE = "booster_profiles"
    private const val KEY = "profiles"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun load(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    fun defaults(context: Context) = GameProfile(
        dnd = Prefs.gameMode(context),
        brightnessLock = false,
        refreshLock = false,
        hud = true,
        mode = Prefs.boostMode(context),
        custom = false
    )

    fun get(context: Context, packageName: String): GameProfile {
        val fallback = defaults(context)
        val item = load(context).optJSONObject(packageName) ?: return fallback
        return GameProfile(
            dnd = item.optBoolean("dnd", fallback.dnd),
            brightnessLock = item.optBoolean("brightness", fallback.brightnessLock),
            refreshLock = item.optBoolean("refresh", fallback.refreshLock),
            hud = item.optBoolean("hud", fallback.hud),
            mode = item.optInt("mode", fallback.mode).coerceIn(Prefs.MODE_BATTERY, Prefs.MODE_PERFORMANCE),
            custom = true
        )
    }

    fun has(context: Context, packageName: String): Boolean =
        load(context).has(packageName)

    fun save(context: Context, packageName: String, profile: GameProfile) {
        val root = load(context)
        root.put(
            packageName,
            JSONObject().apply {
                put("dnd", profile.dnd)
                put("brightness", profile.brightnessLock)
                put("refresh", profile.refreshLock)
                put("hud", profile.hud)
                put("mode", profile.mode)
            }
        )
        prefs(context).edit().putString(KEY, root.toString()).apply()
    }

    fun clear(context: Context, packageName: String) {
        val root = load(context)
        root.remove(packageName)
        prefs(context).edit().putString(KEY, root.toString()).apply()
    }

    fun customCount(context: Context): Int = load(context).length()
}
