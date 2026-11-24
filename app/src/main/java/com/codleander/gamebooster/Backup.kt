package com.codleander.gamebooster

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-file backup of every setting plus the session history, written through the system
 * document picker so nothing leaves the device unless the user picks a destination.
 */
object Backup {

    const val FILE_NAME = "flash-booster-backup.json"
    const val MIME = "application/json"

    private const val SETTINGS_FILE = "booster_settings"
    private const val HISTORY_FILE = "booster_history"

    fun export(context: Context, uri: Uri): Boolean {
        val settings = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE).all
        val history = context.getSharedPreferences(HISTORY_FILE, Context.MODE_PRIVATE)
            .getString("sessions", "[]") ?: "[]"
        val payload = JSONObject().apply {
            put("app", "FLASH BOOSTER")
            put("format", 1)
            put("exported_at", System.currentTimeMillis())
            put("version", BuildConfig.VERSION_NAME)
            put("settings", JSONObject(settings.toMap()))
            put("history", runCatching { JSONArray(history) }.getOrDefault(JSONArray()))
        }
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(payload.toString(2).toByteArray())
            } != null
        }.getOrDefault(false)
    }

    /**
     * Restores the settings and history from a backup produced by [export]. Unknown or
     * malformed keys are ignored, and the history is capped like the live store.
     */
    fun import(context: Context, uri: Uri): Boolean {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return false
        val payload = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val settings = payload.optJSONObject("settings") ?: return false

        val editor = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE).edit()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = settings.opt(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()

        val history = payload.optJSONArray("history")
        if (history != null) {
            val capped = JSONArray()
            val from = (history.length() - MAX_HISTORY).coerceAtLeast(0)
            for (index in from until history.length()) {
                history.optJSONObject(index)?.let { capped.put(it) }
            }
            context.getSharedPreferences(HISTORY_FILE, Context.MODE_PRIVATE).edit()
                .putString("sessions", capped.toString())
                .apply()
        }
        return true
    }

    private const val MAX_HISTORY = 2000
}
