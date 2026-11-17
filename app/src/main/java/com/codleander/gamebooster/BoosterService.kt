package com.codleander.gamebooster

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import java.util.Locale

class BoosterService : Service() {
    companion object {
        const val EXTRA_PACKAGE = "target_package"
        const val ACTION_START = "com.codleander.gamebooster.START"
        const val ACTION_STOP = "com.codleander.gamebooster.STOP"
        private const val CHANNEL_ID = "booster_session"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_MS = 2000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetPackage: String? = null
    private var bubble: View? = null
    private var windowManager: WindowManager? = null

    private val poller = object : Runnable {
        override fun run() {
            val target = targetPackage
            if (target == null) return
            val foreground = foregroundPackage()
            if (foreground == target) {
                updateBubble(true)
                handler.postDelayed(this, POLL_MS)
            } else if (foreground != null && foreground != packageName) {
                stopSelf()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification("Game session monitor ready")
        if (Build.VERSION.SDK_INT >= 29) {
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
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> {
                targetPackage = intent?.getStringExtra(EXTRA_PACKAGE) ?: targetPackage
                if (targetPackage != null && Settings.canDrawOverlays(this)) showBubble()
                handler.removeCallbacks(poller)
                handler.post(poller)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeBubble()
        restoreDnd()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun foregroundPackage(): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 8000L, end)
        val event = android.app.usage.UsageEvents.Event()
        var latestPkg: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val foregroundEvent =
                event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= 29 &&
                    event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED)
            if (foregroundEvent && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                latestPkg = event.packageName
            }
        }
        return latestPkg
    }

    private fun showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val tv = TextView(this).apply {
            text = "⚡"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.neo_bubble)
            elevation = 12f
            contentDescription = "Game Booster floating control"
            setOnClickListener {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                }
            }
            setOnLongClickListener {
                stopSelf()
                true
            }
        }

        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (180 * density).toInt()
        }

        tv.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                        if (moved) {
                            params.x = startX - dx
                            params.y = startY + dy
                            windowManager?.updateViewLayout(v, params)
                            return true
                        }
                    }
                }
                return false
            }
        })

        bubble = tv
        windowManager?.addView(tv, params)
    }

    private fun updateBubble(active: Boolean) {
        (bubble as? TextView)?.text = if (active) "⚡" else "•"
    }

    private fun removeBubble() {
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
    }

    private fun restoreDnd() {
        val prefs = getSharedPreferences("booster", MODE_PRIVATE)
        val previous = prefs.getInt("previous_interruption_filter", -1)
        if (previous >= 0) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(previous)
            }
            prefs.edit().remove("previous_interruption_filter").apply()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Game Booster Session",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shows when the Game Booster session is active."
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
            .setContentTitle("Game Booster")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }
}