package com.codleander.gamebooster

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Do Not Disturb access is a separate system grant (Settings > Notifications > DND access).
 * Every call here reads the real system state back so the UI never lies about DND.
 */
object DndHelper {

    private fun manager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun hasAccess(context: Context): Boolean =
        manager(context).isNotificationPolicyAccessGranted

    fun isOn(context: Context): Boolean {
        val nm = manager(context)
        if (!nm.isNotificationPolicyAccessGranted) return false
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    fun setOn(context: Context, on: Boolean): Boolean {
        val nm = manager(context)
        if (!nm.isNotificationPolicyAccessGranted) return false
        val target = if (on) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        return runCatching {
            nm.setInterruptionFilter(target)
            nm.currentInterruptionFilter == target
        }.getOrDefault(false)
    }

    /** Returns the new DND state, or null when the app still lacks the system grant. */
    fun toggle(context: Context): Boolean? {
        if (!hasAccess(context)) return null
        val target = !isOn(context)
        setOn(context, target)
        return isOn(context)
    }

    /**
     * Puts back the interruption filter a one-off device boost changed. Every entry point that
     * can end such a boost (the app, the widget, the quick-settings tile) restores through
     * here, so DND never stays silently enabled after the boost is over.
     */
    fun restoreDeviceBoost(context: Context) {
        val runtime = context.getSharedPreferences("booster_runtime", Context.MODE_PRIVATE)
        if (!runtime.contains("device_boost_dnd_previous")) return
        val previous = runtime.getInt(
            "device_boost_dnd_previous", NotificationManager.INTERRUPTION_FILTER_ALL
        )
        val nm = manager(context)
        if (nm.isNotificationPolicyAccessGranted &&
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        ) {
            runCatching { nm.setInterruptionFilter(previous) }
        }
        runtime.edit().remove("device_boost_dnd_previous").apply()
    }

    fun openAccessSettings(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
}
