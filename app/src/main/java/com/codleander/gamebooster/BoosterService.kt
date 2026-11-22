package com.codleander.gamebooster

import android.app.*
import android.app.usage.UsageStatsManager
import android.app.AppOpsManager
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.Icon
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class BoosterService : Service() {
    companion object {
        private const val SCREEN_ON_TIMEOUT_MS = 30 * 60 * 1000L
        const val EXTRA_PACKAGE = "target_package"
        const val ACTION_START = "com.codleander.gamebooster.START"
        const val ACTION_STOP = "com.codleander.gamebooster.STOP"
        const val ACTION_REFRESH_HUD = "com.codleander.gamebooster.REFRESH_HUD"
        private const val CHANNEL_ID = "booster_session"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_MS = 2000L
        private const val SESSION_MAX_MS = 24L * 60L * 60L * 1000L
        private const val FOREGROUND_MISSING_GRACE_MS = 15_000L
        private const val AMBIENT_VOLUME = 0.3f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetPackage: String? = null
    private var hud: BoosterHud? = null
    private var ambient: MediaPlayer? = null
    private var fpsMonitor: FpsMonitor? = null
    private var lastFps = 0f
    private var sessionStartedAt = 0L
    private var peakThermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var sessionStartBrightness = -1f
    private var sessionStartScreenTimeout = -1L
    private var screenTimeoutChangedByService = false
    private var sessionStartAt = 0L
    private var activePlayMs = 0L
    private var lastPollAt = 0L
    private var lastForegroundPackage: String? = null
    private var lastForegroundEventAt = 0L
    private var usageAccessMissingSince = 0L
    private var gameForegroundSeen = false
    private var profileDnd = true
    private var profileAwake = true
    private var profileBrightness = false
    private var profileHud = true
    private var profileRefresh = false
    private var refreshLockedByService = false
    private var sessionPausedAt = 0L
    private var dndChangedByService = false
    private var dndChangedByUser = false
    private var brightnessChangedByService = false

    private val poller = object : Runnable {
        override fun run() {
            val target = targetPackage ?: return
            val now = System.currentTimeMillis()
            if (System.currentTimeMillis() - sessionStartedAt >= SESSION_MAX_MS) {
                stopSelf()
                return
            }
            if (!hasUsageAccess()) {
                if (usageAccessMissingSince == 0L) usageAccessMissingSince = now
                if (now - usageAccessMissingSince >= FOREGROUND_MISSING_GRACE_MS) {
                    stopSelf()
                    return
                }
            } else {
                usageAccessMissingSince = 0L
            }
            val foreground = foregroundPackage()
            if (foreground == target) {
                gameForegroundSeen = true
                if (lastPollAt > 0L) {
                    activePlayMs += SessionGuards.capElapsed(now, lastPollAt, POLL_MS * 2L)
                }
            }
            lastPollAt = now
            updateThermalPeak()
            enforceScreenTimeout()
            enforceBrightnessLock()
            persistRuntimeHeartbeat()

            hud?.setLive(foreground == target)
            if (foreground != target && SessionGuards.shouldStopForWrongForeground(
                    foreground, target, packageName, now - sessionStartedAt, FOREGROUND_MISSING_GRACE_MS
                )) {
                stopSelf()
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification(getString(R.string.notif_ready))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!IntegrityCheck.isTrusted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_REFRESH_HUD -> {
                if (sessionStartedAt == 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                hud?.setFpsVisible(Prefs.fpsHud(this))
            }
            else -> {
                val requestedPackage = intent?.getStringExtra(EXTRA_PACKAGE) ?: targetPackage
                if (requestedPackage.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (sessionStartedAt != 0L && targetPackage == requestedPackage) {
                    // Ignore duplicate starts for the same active game. Repeated taps must not
                    // create a second logical session or overwrite restoration state.
                    return START_NOT_STICKY
                }
                if (sessionStartedAt != 0L) {
                    // A replacement session is allowed to start only after every
                    // setting changed by the previous session has been restored.
                    // If restoration fails, keep recovery state intact and stop
                    // instead of starting a new session on top of stale state.
                    if (!finishCurrentSessionForReplacement()) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                if (!isLaunchablePackage(requestedPackage)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                targetPackage = requestedPackage
                sessionStartedAt = System.currentTimeMillis()
                sessionStartAt = sessionStartedAt
                activePlayMs = 0L
                lastPollAt = sessionStartedAt
                profileDnd = intent?.getBooleanExtra("profile_dnd", true) ?: true
                profileAwake = intent?.getBooleanExtra("profile_awake", true) ?: true
                profileBrightness = intent?.getBooleanExtra("profile_brightness", false) ?: false
                profileHud = intent?.getBooleanExtra("profile_hud", true) ?: true
                profileRefresh = intent?.getBooleanExtra("profile_refresh", false) ?: false
                refreshLockedByService = false
                gameForegroundSeen = false
                dndChangedByService = false
                dndChangedByUser = false
                brightnessChangedByService = false
                screenTimeoutChangedByService = false
                lastForegroundPackage = null
                lastForegroundEventAt = 0L
                usageAccessMissingSince = 0L

                val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
                val runtimeEditor = runtime.edit()
                    .putLong("session_started_at", sessionStartedAt)
                    .putString("target_package", targetPackage)
                    .putString("target_label", runCatching {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(targetPackage!!, 0)
                        ).toString()
                    }.getOrDefault(targetPackage ?: "Unknown Game"))
                    .putLong("active_play_ms", 0L)
                    .putLong("last_heartbeat", System.currentTimeMillis())
                    .putInt("peak_thermal", PowerManager.THERMAL_STATUS_NONE)

                if (profileDnd) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    if (nm.isNotificationPolicyAccessGranted &&
                        nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    ) {
                        val previous = nm.currentInterruptionFilter
                        val changed = runCatching {
                            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                        }.isSuccess
                        if (changed) {
                            dndChangedByService = true
                            runtimeEditor
                                .putInt("previous_interruption_filter", previous)
                                .putInt("expected_interruption_filter", NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                                .putBoolean("dnd_changed", true)
                        }
                    }
                }

                if (profileAwake && Settings.System.canWrite(this)) {
                    sessionStartScreenTimeout = Settings.System.getLong(
                        contentResolver,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        -1L
                    )
                    if (sessionStartScreenTimeout > 0L) {
                        runCatching {
                            Settings.System.putLong(
                                contentResolver,
                                Settings.System.SCREEN_OFF_TIMEOUT,
                                SCREEN_ON_TIMEOUT_MS
                            )
                            val appliedTimeout = Settings.System.getLong(
                                contentResolver,
                                Settings.System.SCREEN_OFF_TIMEOUT,
                                SCREEN_ON_TIMEOUT_MS
                            )
                            screenTimeoutChangedByService = true
                            runtimeEditor
                                .putLong("previous_screen_timeout", sessionStartScreenTimeout)
                                .putLong("applied_screen_timeout", appliedTimeout)
                                .putBoolean("screen_timeout_changed", true)
                        }
                    }
                }

                if (profileBrightness && Settings.System.canWrite(this)) {
                    sessionStartBrightness = Settings.System.getFloat(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        -1f
                    )
                    if (sessionStartBrightness >= 0f) {
                        val original = sessionStartBrightness.toInt().coerceIn(0, 255)
                        runCatching {
                            Settings.System.putInt(
                                contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS,
                                original
                            )
                            brightnessChangedByService = true
                            runtimeEditor
                                .putInt("previous_brightness", original)
                                .putBoolean("brightness_changed", true)
                        }
                    }
                }

                if (profileRefresh && RefreshRateHelper.canControl(this)) {
                    val targetHz = RefreshRateHelper.maxHz(this)
                    if (targetHz != null && RefreshRateHelper.apply(this, targetHz)) {
                        refreshLockedByService = true
                        runtimeEditor
                            .putBoolean("refresh_changed", true)
                            .putFloat("refresh_locked_hz", targetHz)
                    }
                }

                runtimeEditor.apply()
                startAmbient()
                if (targetPackage != null && profileHud) {
                    runCatching { showHud() }
                }
                handler.removeCallbacks(poller)
                handler.post(poller)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideHud()
        stopAmbient()
        persistRuntimeHeartbeat()
        saveSessionHistory()
        val dndRestored = restoreDnd()
        val timeoutRestored = restoreScreenTimeout()
        val brightnessRestored = restoreBrightness()
        val refreshRestored = restoreRefreshRate()
        if (dndRestored && timeoutRestored && brightnessRestored && refreshRestored) {
            getSharedPreferences("booster_runtime", MODE_PRIVATE).edit().clear().apply()
        } else {
            persistRuntimeHeartbeat()
        }
        // A session can also be ended from the overlay, a notification action or the system,
        // which never touches the widget or the tile on its own.
        BoosterWidgetProvider.refreshAll(this)
        BoosterTileService.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isLaunchablePackage(pkg: String): Boolean =
        runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

    private fun foregroundPackage(): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 15_000L, end)
        val event = android.app.usage.UsageEvents.Event()

        var latestPackage = lastForegroundPackage
        var latestTime = lastForegroundEventAt

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp < latestTime) continue

            when (event.eventType) {
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND,
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName
                }

                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND,
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> {
                    // Only clear the package that was actually known to be
                    // foreground. Background events from another package must
                    // not erase a newer foreground game transition.
                    if (event.packageName == latestPackage) {
                        latestTime = event.timeStamp
                        latestPackage = null
                    }
                }
            }
        }

        if (latestTime > lastForegroundEventAt) {
            lastForegroundEventAt = latestTime
            lastForegroundPackage = latestPackage
        }

        return lastForegroundPackage
    }

    private fun showHud() {
        if (!Settings.canDrawOverlays(this)) return
        if (hud != null) {
            hud?.setFpsVisible(Prefs.fpsHud(this))
            return
        }
        val created = BoosterHud(this, hudListener)
        hud = created
        created.show()
        fpsMonitor?.stop()
        fpsMonitor = FpsMonitor { lastFps = it }.also { it.start() }
        handler.removeCallbacks(hudTick)
        handler.post(hudTick)
    }

    private fun hideHud() {
        handler.removeCallbacks(hudTick)
        fpsMonitor?.stop()
        fpsMonitor = null
        lastFps = 0f
        hud?.removeAll()
        hud = null
    }

    /** The ambient loop belongs to the session: it starts with it and stops with it. */
    private fun startAmbient() {
        stopAmbient()
        if (!Prefs.ambient(this)) return
        val pack = Prefs.soundPack(this)
        if (!SoundPacks.hasAudio(pack)) return
        runCatching {
            ambient = MediaPlayer.create(this, SoundPacks.ambient(pack))?.apply {
                isLooping = true
                setVolume(AMBIENT_VOLUME, AMBIENT_VOLUME)
                start()
            }
        }.onFailure { ambient = null }
    }

    private fun stopAmbient() {
        val player = ambient ?: return
        ambient = null
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private val hudTick = object : Runnable {
        override fun run() {
            val current = hud ?: return
            val elapsed = if (sessionStartedAt > 0L) {
                System.currentTimeMillis() - sessionStartedAt
            } else {
                0L
            }
            current.update(
                elapsed,
                lastFps,
                DeviceStats.memory(this@BoosterService).usedPercent,
                DeviceStats.battery(this@BoosterService).temperatureC
            )
            handler.postDelayed(this, 1000L)
        }
    }

    private val hudListener = object : BoosterHud.Listener {
        override fun onEndSession() {
            stopSelf()
        }

        override fun onClearRam() {
            Thread {
                val result = RamCleaner.free(this@BoosterService, 12)
                handler.post {
                    val message = if (result.freedMb > 0) {
                        getString(R.string.ram_freed, result.freedMb)
                    } else {
                        getString(R.string.ram_cleared, result.killed)
                    }
                    runCatching {
                        Toast.makeText(this@BoosterService, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        override fun onOpenApp() {
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(launch) }
        }

        override fun onDndChanged() {
            dndChangedByUser = true
        }
    }

    private fun updateThermalPeak() {
        if (Build.VERSION.SDK_INT >= 29) {
            val status = (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus
            if (status > peakThermalStatus) peakThermalStatus = status
        }
    }

    private fun enforceScreenTimeout() {
        if (!profileAwake || !screenTimeoutChangedByService || !Settings.System.canWrite(this)) return
        val current = runCatching {
            Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrNull() ?: return
        if (current < SCREEN_ON_TIMEOUT_MS) {
            runCatching { Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_ON_TIMEOUT_MS) }
        }
    }

    private fun enforceBrightnessLock() {
        if (!profileBrightness || !brightnessChangedByService || !Settings.System.canWrite(this)) return
        val locked = sessionStartBrightness.toInt().coerceIn(0, 255)
        val current = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: return
        if (current != locked) {
            runCatching {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, locked)
            }
        }
    }

    private fun persistRuntimeHeartbeat() {
        if (sessionStartedAt <= 0L || targetPackage.isNullOrBlank()) return
        getSharedPreferences("booster_runtime", MODE_PRIVATE).edit()
            .putLong("last_heartbeat", System.currentTimeMillis())
            .putLong("active_play_ms", activePlayMs)
            .putInt("peak_thermal", peakThermalStatus)
            .apply()
    }

    private fun finishCurrentSessionForReplacement(): Boolean {
        handler.removeCallbacks(poller)
        hideHud()
        stopAmbient()
        saveSessionHistory()

        val dndRestored = restoreDnd()
        val timeoutRestored = restoreScreenTimeout()
        val brightnessRestored = restoreBrightness()
        val refreshRestored = restoreRefreshRate()
        if (!dndRestored || !timeoutRestored || !brightnessRestored || !refreshRestored) {
            // Keep the active session and runtime recovery markers intact.
            // onDestroy() will get another chance to restore them.
            persistRuntimeHeartbeat()
            return false
        }

        getSharedPreferences("booster_runtime", MODE_PRIVATE).edit().clear().apply()

        targetPackage = null
        sessionStartedAt = 0L
        sessionStartAt = 0L
        activePlayMs = 0L
        lastPollAt = 0L
        lastForegroundPackage = null
        lastForegroundEventAt = 0L
        sessionStartBrightness = -1f
        brightnessChangedByService = false
        profileRefresh = false
        refreshLockedByService = false
        sessionStartScreenTimeout = -1L
        screenTimeoutChangedByService = false
        dndChangedByService = false
        dndChangedByUser = false
        peakThermalStatus = PowerManager.THERMAL_STATUS_NONE
        usageAccessMissingSince = 0L
        return true
    }

    private fun saveSessionHistory() {
        if (sessionStartedAt == 0L || targetPackage == null) return
        if (!gameForegroundSeen && activePlayMs <= 0L) return
        val duration = activePlayMs.coerceAtLeast(0L)
        val label = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(targetPackage!!, 0)
            ).toString()
        }.getOrElse { targetPackage ?: "Unknown Game" }
        val prefs = getSharedPreferences("booster_history", MODE_PRIVATE)
        val sessions = runCatching { JSONArray(prefs.getString("sessions", "[]")) }.getOrDefault(JSONArray())
        sessions.put(JSONObject().apply {
            put("start", sessionStartedAt)
            put("end", System.currentTimeMillis())
            put("duration", duration)
            put("package", targetPackage)
            put("label", label)
            put("peakThermal", peakThermalStatus)
        })
        while (sessions.length() > 2000) sessions.remove(0)
        prefs.edit()
            .putString("sessions", sessions.toString())
            .putString("last_label", label)
            .putString("last_package", targetPackage)
            .putLong("last_duration_ms", duration)
            .putInt("last_peak_thermal", peakThermalStatus)
            .apply()
    }

    private fun formatDuration(ms:Long):String { val s=ms/1000; return String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s%3600)/60,s%60) }
    private fun thermalLabel(s:Int):String=when(s){PowerManager.THERMAL_STATUS_NONE->"NORMAL";PowerManager.THERMAL_STATUS_LIGHT->"LIGHT";PowerManager.THERMAL_STATUS_MODERATE->"MODERATE";PowerManager.THERMAL_STATUS_SEVERE->"SEVERE";PowerManager.THERMAL_STATUS_CRITICAL->"CRITICAL";PowerManager.THERMAL_STATUS_EMERGENCY->"EMERGENCY";PowerManager.THERMAL_STATUS_SHUTDOWN->"SHUTDOWN";else->"N/A"}

    private fun restoreScreenTimeout(): Boolean {
        if (!screenTimeoutChangedByService) return true
        if (!Settings.System.canWrite(this)) return false

        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        val previous = runtime.getLong("previous_screen_timeout", sessionStartScreenTimeout)
        val applied = runtime.getLong("applied_screen_timeout", SCREEN_ON_TIMEOUT_MS)
        if (previous <= 0L) return true

        val current = runCatching {
            Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrNull() ?: return false

        if (!SessionGuards.shouldRestoreLong(true, previous, current, applied)) {
            sessionStartScreenTimeout = -1L
            screenTimeoutChangedByService = false
            return true
        }

        val restored = runCatching {
            Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, previous)
            Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) == previous
        }.getOrDefault(false)

        if (restored) {
            sessionStartScreenTimeout = -1L
            screenTimeoutChangedByService = false
        }
        return restored
    }

    private fun restoreBrightness(): Boolean {
        if (!brightnessChangedByService) return true
        if (!Settings.System.canWrite(this)) return false

        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        val previous = runtime.getInt("previous_brightness", sessionStartBrightness.toInt().coerceIn(0, 255))
        if (previous < 0) return true

        val current = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: return false

        if (!SessionGuards.shouldRestoreInt(true, previous, current, previous)) {
            sessionStartBrightness = -1f
            brightnessChangedByService = false
            return true
        }

        val restored = runCatching {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, previous)
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) == previous
        }.getOrDefault(false)

        if (restored) {
            sessionStartBrightness = -1f
            brightnessChangedByService = false
        }
        return restored
    }

    private fun restoreRefreshRate(): Boolean {
        if (!refreshLockedByService) return true
        val restored = RefreshRateHelper.restore(this)
        if (restored) refreshLockedByService = false
        return restored
    }

    private fun restoreDnd(): Boolean {
        if (!dndChangedByService) return true
        if (dndChangedByUser) return true

        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        val previous = runtime.getInt("previous_interruption_filter", -1)
        val expected = runtime.getInt(
            "expected_interruption_filter",
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        if (previous < 0) return true

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return false
        if (nm.currentInterruptionFilter != expected) {
            dndChangedByService = false
            return true
        }

        val restored = runCatching {
            nm.setInterruptionFilter(previous)
            nm.currentInterruptionFilter == previous
        }.getOrDefault(false)

        if (restored) {
            dndChangedByService = false
            dndChangedByUser = false
        }
        return restored
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.notif_session_active)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lightning)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
             .setOngoing(true)
            .setContentIntent(pending)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_lightning),
                getString(R.string.notif_stop),
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, BoosterService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            ).build())
            .build()
    }
}