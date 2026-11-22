package com.codleander.gamebooster

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap starts a boost for the last played game (or a full
 * device boost), another tap ends the running session.
 */
class BoosterTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (isSessionActive(this)) {
            stopSession(this)
            render()
            return
        }
        val launch = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_QUICK_BOOST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        collapseAndLaunch(launch, pending)
    }

    /**
     * Android 14 replaced the Intent overload with a PendingIntent one, but that overload does
     * not exist on API 26-33, so older devices must keep using the deprecated call.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapseAndLaunch(launch: Intent, pending: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(launch)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val active = isSessionActive(this)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (active) R.drawable.ic_lightning else R.drawable.ic_bolt
        )
        tile.label = getString(if (active) R.string.tile_active else R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (active) R.string.widget_sub_active else R.string.widget_sub_idle)
        }
        tile.updateTile()
    }

    companion object {
        /**
         * A game session is stopped through the service so it can restore every setting it
         * changed; a device boost has no service and only needs its runtime flags cleared.
         */
        fun stopSession(context: android.content.Context) {
            val runtime = context.getSharedPreferences("booster_runtime", android.content.Context.MODE_PRIVATE)
            val game = runtime.getString("target_label", null)
            if (!game.isNullOrBlank()) {
                context.stopService(Intent(context, BoosterService::class.java))
            } else {
                DndHelper.restoreDeviceBoost(context)
                runtime.edit()
                    .remove("device_boost_active")
                    .remove("device_boost_started_at")
                    .apply()
            }
            BoosterWidgetProvider.refreshAll(context)
        }

        fun isSessionActive(context: android.content.Context): Boolean {
            val runtime = context.getSharedPreferences("booster_runtime", android.content.Context.MODE_PRIVATE)
            val game = runtime.getString("target_label", null)
            if (!game.isNullOrBlank()) {
                // A game session is only real while its service keeps writing the heartbeat.
                return !SessionGuards.isSessionStale(
                    System.currentTimeMillis(),
                    runtime.getLong("last_heartbeat", 0L)
                )
            }
            return runtime.getBoolean("device_boost_active", false)
        }

        fun refresh(context: android.content.Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    android.content.ComponentName(context, BoosterTileService::class.java)
                )
            }
        }
    }
}
