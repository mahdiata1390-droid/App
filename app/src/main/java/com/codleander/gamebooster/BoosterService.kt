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
            val pkg = foregroundPackage()
            if (targetPackage != null && pkg == targetPackage) {
                updateBubble(true)
                handler.postDelayed(this, POLL_MS)
            } else if (targetPackage != null && pkg != packageName) {
                stopSelf()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Game session monitor ready"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun foregroundPackage(): String? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 8000, end)
        val event = android.app.usage.UsageEvents.Event()
        var latestPkg: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if ((event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                 event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) &&
                event.timeStamp >= latestTime) {
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
            setOnClickListener {
                updateBubble(true)
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
        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * resources.displayMetrics.density).toInt()
            y = (180 * resources.displayMetrics.density).toInt()
        }
        tv.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y; moved = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Game Booster Session", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when the Game Booster session is active."
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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