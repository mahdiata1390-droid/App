package com.codleander.gamebooster

import android.app.*
import android.app.AppOpsManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import android.media.ToneGenerator
import android.media.AudioManager
import java.util.Locale
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var ramView: TextView
    private lateinit var thermalView: TextView
    private lateinit var refreshView: TextView
    private lateinit var gamesContainer: LinearLayout
    private lateinit var lightningIcon: ImageView
    private lateinit var statusView: TextView
    private lateinit var boostView: TextView
    private var selectedPackage: String? = null
    private var selectedLabel: String? = null
    private lateinit var dndState: TextView
    private lateinit var usageState: TextView
    private lateinit var overlayState: TextView
    private lateinit var historyHint: TextView
    private var tone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ramView = findViewById(R.id.ram)
        thermalView = findViewById(R.id.thermal)
        refreshView = findViewById(R.id.refresh)
        gamesContainer = findViewById(R.id.games)
        lightningIcon = findViewById(R.id.lightningIcon)
        statusView = findViewById(R.id.status)
        boostView = findViewById(R.id.boost)
        dndState = findViewById(R.id.dndPermissionState)
        usageState = findViewById(R.id.usagePermissionState)
        overlayState = findViewById(R.id.overlayPermissionState)
        historyHint = findViewById(R.id.historyHint)
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)

        boostView.setOnClickListener {
            pulse(boostView); pulse(lightningIcon); feedback(true)
            statusView.text = if (selectedLabel != null) "READY • ${selectedLabel!!.uppercase(Locale.ROOT)}" else "SELECT A GAME BELOW TO START A BOOST SESSION"
        }
        findViewById<Button>(R.id.dnd).setOnClickListener { pulse(it); feedback(false); openDndSettings() }
        findViewById<Button>(R.id.refreshStats).setOnClickListener { pulse(it); feedback(false); refreshAll() }
        findViewById<Button>(R.id.dndPermission).setOnClickListener { pulse(it); feedback(false); openDndSettings() }
        findViewById<Button>(R.id.usagePermission).setOnClickListener { pulse(it); feedback(false); openUsageSettings() }
        findViewById<Button>(R.id.overlayPermission).setOnClickListener { pulse(it); feedback(false); openOverlaySettings() }
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (::dndState.isInitialized) updatePermissionStates()
    }

    override fun onDestroy() {
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun refreshAll() {
        updateStats()
        loadGames()
        updatePermissionStates()
        showHistorySummary()
    }

    private fun feedback(strong: Boolean) {
        tone?.startTone(if (strong) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 90)
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            val duration = if (strong) 35L else 20L
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            else { @Suppress("DEPRECATION") vibrator.vibrate(duration) }
        }
    }

    private fun pulse(view: View) {
        view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_electric))
    }

    private fun updateStats() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val used = info.totalMem - info.availMem
        ramView.text = String.format(Locale.US, "RAM\n%.1f / %.1f GB", used / 1073741824.0, info.totalMem / 1073741824.0)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        thermalView.text = "THERMAL\n" + thermalLabel(thermal)
        @Suppress("DEPRECATION")
        refreshView.text = String.format(Locale.US, "DISPLAY\n%.0f Hz", windowManager.defaultDisplay.refreshRate)
    }

    private fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NORMAL"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "N/A"
    }

    private fun loadGames() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedWith(
                compareByDescending<android.content.pm.ResolveInfo> {
                    it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME
                }.thenBy { it.loadLabel(pm).toString().lowercase(Locale.ROOT) }
            )

        gamesContainer.removeAllViews()
        apps.forEach { ri ->
            val isGame = ri.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 12, 10, 12)
                background = getDrawable(R.drawable.neo_card)
                elevation = 6f
            }
            val icon = ImageView(this).apply {
                setImageDrawable(ri.loadIcon(pm))
                layoutParams = LinearLayout.LayoutParams(44, 44)
            }
            val nameBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12 }
            }
            val name = TextView(this).apply {
                text = ri.loadLabel(pm)
                textSize = 15f
                setTextColor(Color.rgb(234, 248, 255))
            }
            val type = TextView(this).apply {
                text = if (isGame) "GAME • READY" else "APP • LAUNCHABLE"
                textSize = 10f
                setTextColor(Color.rgb(127, 166, 196))
            }
            nameBox.addView(name); nameBox.addView(type)

            row.setOnClickListener {
                selectedPackage = ri.activityInfo.packageName
                selectedLabel = ri.loadLabel(pm).toString()
                statusView.text = "SELECTED • ${selectedLabel!!.uppercase(Locale.ROOT)} • READY TO BOOST"
            }

            val launch = Button(this).apply {
                text = "BOOST"
                setTextColor(Color.rgb(89, 216, 255))
                background = getDrawable(R.drawable.neo_button)
                elevation = 4f
                setOnClickListener {
                    pulse(this); pulse(lightningIcon); feedback(true)
                    startGameSession(ri.activityInfo.packageName, ri.loadLabel(pm).toString())
                }
            }
            row.addView(icon); row.addView(nameBox); row.addView(launch)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 12
            gamesContainer.addView(row, lp)
        }
    }

    private fun startGameSession(pkg: String, label: String) {
        selectedPackage = pkg
        selectedLabel = label
        getSharedPreferences("game_profiles", MODE_PRIVATE).edit()
            .putString("${pkg}_label", label)
            .putInt("${pkg}_launches", getSharedPreferences("game_profiles", MODE_PRIVATE).getInt("${pkg}_launches", 0) + 1)
            .apply()
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (hasDndAccess()) {
            getSharedPreferences("booster", MODE_PRIVATE).edit()
                .putInt("previous_interruption_filter", notificationManager.currentInterruptionFilter)
                .apply()
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }

        if (Settings.canDrawOverlays(this)) {
            val serviceIntent = Intent(this, BoosterService::class.java).apply {
                action = BoosterService.ACTION_START
                putExtra(BoosterService.EXTRA_PACKAGE, pkg)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            statusView.text = "BOOST SESSION ACTIVE • " + label.uppercase(Locale.ROOT)
        } else {
            statusView.text = "GAME LAUNCHED • ENABLE OVERLAY FOR FLOATING BOOST"
        }

        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun openDndSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun openUsageSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
    }

    private fun showHistorySummary() {
        val prefs = getSharedPreferences("booster_history", MODE_PRIVATE)
        val sessions = runCatching { JSONArray(prefs.getString("sessions", "[]")) }.getOrDefault(JSONArray())
        val now = java.time.LocalDate.now()
        var todayMs = 0L
        var monthMs = 0L
        val byGame = linkedMapOf<String, Long>()
        for (i in 0 until sessions.length()) {
            val o = sessions.optJSONObject(i) ?: continue
            val start = o.optLong("start", 0L)
            val duration = o.optLong("duration", 0L)
            if (start <= 0L || duration <= 0L) continue
            val date = java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            if (date == now) todayMs += duration
            if (date.year == now.year && date.month == now.month) {
                monthMs += duration
                val label = o.optString("label", "Unknown")
                byGame[label] = (byGame[label] ?: 0L) + duration
            }
        }
        val today = formatPlayTime(todayMs)
        val month = formatPlayTime(monthMs)
        val top = byGame.entries.sortedByDescending { it.value }.take(3).joinToString("  •  ") {
            "${it.key}: ${formatPlayTime(it.value)}"
        }
        historyHint.text = "TODAY  $today\nTHIS MONTH  $month\n" + if (top.isBlank()) "No sessions this month yet." else top
    }

    private fun formatPlayTime(ms: Long): String {
        val totalMinutes = ms / 60000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun updateHistoryOnly() {
        showHistorySummary()
    }

    private fun showLastSessionOrReady() {
        val prefs = getSharedPreferences("booster_history", MODE_PRIVATE)
        val label = prefs.getString("last_label", null)
        val duration = prefs.getLong("last_duration_ms", 0L)
        if (label != null && duration > 0L) {
            val minutes = duration / 60000L
            statusView.text = "LAST SESSION • ${label.uppercase(Locale.ROOT)} • ${minutes} MIN"
        } else {
            statusView.text = "SYSTEM READY • REAL DEVICE DATA"
        }
    }

    private fun updatePermissionStates() {
        dndState.text = if (hasDndAccess()) "GAME MODE • ENABLED" else "GAME MODE • NOT ENABLED"
        usageState.text = if (hasUsageAccess()) "GAME DETECTION • ENABLED" else "GAME DETECTION • NOT ENABLED"
        overlayState.text = if (Settings.canDrawOverlays(this)) "FLOATING BOOSTER • ENABLED" else "FLOATING BOOSTER • NOT ENABLED"
    }

    private fun hasDndAccess(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }
}