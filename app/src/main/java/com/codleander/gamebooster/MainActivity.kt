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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager.LayoutParams
import java.util.Locale
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var ramView: TextView
    private lateinit var thermalView: TextView
    private lateinit var refreshView: TextView
    private lateinit var batteryView: TextView
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
    private lateinit var timerView: TextView
    private lateinit var networkView: TextView
    private lateinit var pingView: TextView
    private lateinit var notificationState: TextView
    private var sessionStartedAt = 0L
    private var selectedProfile = Profile()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ramView = findViewById(R.id.ram)
        thermalView = findViewById(R.id.thermal)
        refreshView = findViewById(R.id.refresh)
        batteryView = findViewById(R.id.battery)
        gamesContainer = findViewById(R.id.games)
        lightningIcon = findViewById(R.id.lightningIcon)
        statusView = findViewById(R.id.status)
        boostView = findViewById(R.id.boost)
        dndState = findViewById(R.id.dndPermissionState)
        usageState = findViewById(R.id.usagePermissionState)
        overlayState = findViewById(R.id.overlayPermissionState)
        historyHint = findViewById(R.id.historyHint)
        timerView = findViewById(R.id.sessionTimer)
        networkView = findViewById(R.id.networkStatus)
        pingView = findViewById(R.id.pingStatus)
        notificationState = findViewById(R.id.notificationPermissionState)
        applyGlobalUiPolish()
        recoverStaleRuntimeIfNeeded()
        startLiveDashboard()
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)

        boostView.setOnClickListener {
            pulse(boostView); pulse(lightningIcon); feedback(true)
            val pkg = selectedPackage
            val label = selectedLabel
            if (pkg != null && label != null) startGameSession(pkg, label)
            else statusView.text = "SELECT A GAME BELOW TO START A BOOST SESSION"
        }
        findViewById<Button>(R.id.dnd).setOnClickListener { pulse(it); feedback(false); openDndSettings() }
        findViewById<Button>(R.id.refreshStats).setOnClickListener { pulse(it); feedback(false); refreshAll() }
        findViewById<Button>(R.id.dndPermission).setOnClickListener { pulse(it); feedback(false); openDndSettings() }
        findViewById<Button>(R.id.usagePermission).setOnClickListener { pulse(it); feedback(false); openUsageSettings() }
        findViewById<Button>(R.id.overlayPermission).setOnClickListener { pulse(it); feedback(false); openOverlaySettings() }
        findViewById<Button>(R.id.notificationPermission).setOnClickListener { pulse(it); feedback(false); openNotificationPermission() }
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (::dndState.isInitialized) {
            recoverStaleRuntimeIfNeeded()
            syncActiveSessionStart()
            updatePermissionStates()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun startLiveDashboard() {
        uiHandler.post(object : Runnable {
            override fun run() {
                updateStats()
                updateNetwork()
                updatePing()
                updateSessionTimer()
                uiHandler.postDelayed(this, 1000L)
            }
        })
    }

    private fun applyGlobalUiPolish() {
        // Do not keep the booster window awake unconditionally.
    }

    private fun recoverStaleRuntimeIfNeeded() {
        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        val start = runtime.getLong("session_started_at", 0L)
        if (start <= 0L) return

        val heartbeat = runtime.getLong("last_heartbeat", 0L)
        val now = System.currentTimeMillis()
        if (heartbeat > 0L && now - heartbeat < 12_000L) return

        val target = runtime.getString("target_package", null)
        val label = runtime.getString("target_label", target ?: "Unknown Game") ?: "Unknown Game"
        val activePlayMs = runtime.getLong("active_play_ms", 0L)
        val recoveredDuration = activePlayMs.coerceAtLeast((now - start).coerceAtLeast(0L).coerceAtMost(2_000L))

        // Restore only values that this booster session actually changed.
        if (runtime.getBoolean("dnd_changed", false)) {
            val previous = runtime.getInt("previous_interruption_filter", -1)
            val expected = runtime.getInt(
                "expected_interruption_filter",
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (previous >= 0 && nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter == expected
            ) {
                runCatching { nm.setInterruptionFilter(previous) }
            }
        }

        if (runtime.getBoolean("screen_timeout_changed", false) &&
            Settings.System.canWrite(this)
        ) {
            val previous = runtime.getLong("previous_screen_timeout", -1L)
            if (previous > 0L) {
                runCatching {
                    Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, previous)
                }
            }
        }

        if (runtime.getBoolean("brightness_changed", false) &&
            Settings.System.canWrite(this)
        ) {
            val previous = runtime.getInt("previous_brightness", -1)
            if (previous >= 0) {
                runCatching {
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, previous)
                }
            }
        }

        if (!target.isNullOrBlank() && recoveredDuration > 0L) {
            val historyPrefs = getSharedPreferences("booster_history", MODE_PRIVATE)
            val sessions = runCatching {
                JSONArray(historyPrefs.getString("sessions", "[]"))
            }.getOrDefault(JSONArray())
            sessions.put(org.json.JSONObject().apply {
                put("start", start)
                put("end", now)
                put("duration", recoveredDuration)
                put("package", target)
                put("label", label)
                put("peakThermal", runtime.getInt("peak_thermal", PowerManager.THERMAL_STATUS_NONE))
                put("recovered", true)
            })
            while (sessions.length() > 2000) sessions.remove(0)
            historyPrefs.edit()
                .putString("sessions", sessions.toString())
                .putString("last_label", label)
                .putString("last_package", target)
                .putLong("last_duration_ms", recoveredDuration)
                .apply()
        }

        runtime.edit().clear().apply()
        statusView.text = "RECOVERED LAST GAME SESSION • SETTINGS RESTORED"
    }

    private fun syncActiveSessionStart() {
        val stored = getSharedPreferences("booster_runtime", MODE_PRIVATE)
            .getLong("session_started_at", 0L)
        sessionStartedAt = stored
    }

    private fun updateSessionTimer() {
        syncActiveSessionStart()
        if (sessionStartedAt <= 0L) {
            timerView.text = "SESSION\n00:00:00"
            return
        }
        val total = ((System.currentTimeMillis() - sessionStartedAt) / 1000L).coerceAtLeast(0L)
        timerView.text = String.format(
            Locale.US,
            "SESSION\n%02d:%02d:%02d",
            total / 3600,
            (total % 3600) / 60,
            total % 60
        )
    }

    private var pingThread: Thread? = null

    private fun updatePing() {
        if (pingThread?.isAlive == true) return
        pingThread = Thread {
            val start = System.nanoTime()
            val ok = runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("1.1.1.1", 443), 1200)
                }
                true
            }.getOrDefault(false)
            val ms = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            runOnUiThread {
                if (isFinishing || isDestroyedCompat()) return@runOnUiThread
                pingView.text = if (ok) "LATENCY\n${ms} ms" else "LATENCY\nTIMEOUT"
            }
        }.also { it.start() }
    }

    private fun isDestroyedCompat(): Boolean =
        Build.VERSION.SDK_INT >= 17 && isDestroyed

    private fun updateNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(n)
        val type = when {
            caps == null -> "OFFLINE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            else -> "ONLINE"
        }
        networkView.text = "NETWORK\n$type"
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
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        batteryView.text = if (percent >= 0) "BATTERY\n$percent%${if (charging) " • CHARGING" else ""}" else "BATTERY\nN/A"
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
            .filter {
                val info = it.activityInfo.applicationInfo
                info.category == ApplicationInfo.CATEGORY_GAME ||
                    (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.ROOT) }

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
                val launches = getSharedPreferences("game_profiles", MODE_PRIVATE).getInt("${ri.activityInfo.packageName}_launches", 0)
                text = if (isGame) "GAME • READY • $launches BOOSTS" else "APP • LAUNCHABLE • $launches BOOSTS"
                textSize = 10f
                setTextColor(Color.rgb(127, 166, 196))
            }
            nameBox.addView(name); nameBox.addView(type)

            row.setOnClickListener {
                selectedPackage = ri.activityInfo.packageName
                selectedLabel = ri.loadLabel(pm).toString()
                selectedProfile = loadProfile(selectedPackage!!)
                statusView.text = "SELECTED • ${selectedLabel!!.uppercase(Locale.ROOT)} • READY TO BOOST"
            }

            val profile = Button(this).apply {
                text = "PROFILE"
                setTextColor(Color.rgb(89, 216, 255)); background=getDrawable(R.drawable.neo_button); elevation=4f
                setOnClickListener { openProfileEditor(ri.activityInfo.packageName, ri.loadLabel(pm).toString()) }
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
            row.addView(icon)
            row.addView(nameBox)
            row.addView(profile)
            row.addView(launch)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 12
            gamesContainer.addView(row, lp)
        }
    }

    private fun loadProfile(pkg: String): Profile {
        val p = getSharedPreferences("game_profiles", MODE_PRIVATE)
        return Profile(p.getBoolean("${pkg}_dnd", true), p.getBoolean("${pkg}_awake", true), p.getBoolean("${pkg}_brightness", false), p.getBoolean("${pkg}_hud", true))
    }
    private fun saveProfile(pkg: String, profile: Profile) {
        getSharedPreferences("game_profiles", MODE_PRIVATE).edit().putBoolean("${pkg}_dnd", profile.dnd).putBoolean("${pkg}_awake", profile.keepAwake).putBoolean("${pkg}_brightness", profile.lockBrightness).putBoolean("${pkg}_hud", profile.hud).apply()
    }
    private fun openProfileEditor(pkg: String, label: String) {
        val p = loadProfile(pkg)
        val items = arrayOf("Gaming DND", "Keep Screen On", "Lock Current Brightness", "Live HUD")
        val checked = booleanArrayOf(p.dnd,p.keepAwake,p.lockBrightness,p.hud)
        AlertDialog.Builder(this).setTitle("GAME PROFILE • $label").setMultiChoiceItems(items, checked) { _, which, value -> checked[which]=value }
            .setPositiveButton("SAVE") { _, _ ->
                val profile=Profile(checked[0],checked[1],checked[2],checked[3]); saveProfile(pkg, profile); selectedProfile=profile
                if ((profile.lockBrightness || profile.keepAwake) && !Settings.System.canWrite(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                }
                statusView.text="PROFILE SAVED • ${label.uppercase(Locale.ROOT)}"
            }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun startGameSession(pkg: String, label: String) {
        selectedPackage = pkg
        selectedLabel = label
        selectedProfile = loadProfile(pkg)

        val prefs = getSharedPreferences("game_profiles", MODE_PRIVATE)
        prefs.edit()
            .putString("${pkg}_label", label)
            .putInt("${pkg}_launches", prefs.getInt("${pkg}_launches", 0) + 1)
            .apply()

        var sessionStarted = false
        if (hasUsageAccess()) {
            val serviceIntent = Intent(this, BoosterService::class.java).apply {
                action = BoosterService.ACTION_START
                putExtra(BoosterService.EXTRA_PACKAGE, pkg)
                putExtra("profile_dnd", selectedProfile.dnd)
                putExtra("profile_awake", selectedProfile.keepAwake)
                putExtra("profile_brightness", selectedProfile.lockBrightness)
                putExtra("profile_hud", selectedProfile.hud)
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                sessionStartedAt = System.currentTimeMillis()
                sessionStarted = true
                statusView.text = "BOOST SESSION ACTIVE • " + label.uppercase(Locale.ROOT)
            } catch (e: SecurityException) {
                statusView.text = "GAME LAUNCHED • BOOST SERVICE PERMISSION BLOCKED"
            } catch (e: RuntimeException) {
                statusView.text = "GAME LAUNCHED • BOOST SERVICE COULD NOT START"
            }
        } else {
            statusView.text = "GAME LAUNCHED • ENABLE GAME DETECTION FOR BOOST SESSION"
        }

        if (!sessionStarted) sessionStartedAt = 0L

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

    private fun openNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        } else {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) updatePermissionStates()
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
        val peak = (0 until sessions.length()).mapNotNull { sessions.optJSONObject(it)?.optInt("peakThermal", -1) }.maxOrNull() ?: -1
        val top = byGame.entries.sortedByDescending { it.value }.take(3).joinToString("  •  ") {
            "${it.key}: ${formatPlayTime(it.value)}"
        }
        val peakLabel = if (peak >= 0) thermalLabel(peak) else "N/A"
        historyHint.text = "TODAY  $today\nTHIS MONTH  $month\nPEAK THERMAL  $peakLabel\n" + if (top.isBlank()) "No sessions this month yet." else "TOP: $top"
        val chartData: List<Pair<String, Long>> = byGame.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { entry -> Pair(entry.key, entry.value) }
        findViewById<HistoryChartView>(R.id.historyChart).setData(chartData)
    }

    private fun formatPlayTime(ms: Long): String {
        val totalMinutes = ms / 60000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun onSessionEnded() { showLastSessionOrReady(); showHistorySummary(); sessionStartedAt=0L }

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

    data class Profile(val dnd:Boolean=true,val keepAwake:Boolean=true,val lockBrightness:Boolean=false,val hud:Boolean=true)

    private fun updatePermissionStates() {
        dndState.text = if (hasDndAccess()) "GAME MODE • ENABLED" else "GAME MODE • NOT ENABLED"
        usageState.text = if (hasUsageAccess()) "GAME DETECTION • ENABLED" else "GAME DETECTION • NOT ENABLED"
        overlayState.text = if (Settings.canDrawOverlays(this)) "FLOATING BOOSTER • ENABLED" else "FLOATING BOOSTER • NOT ENABLED"
        notificationState.text = if (hasNotificationPermission()) "NOTIFICATIONS • ENABLED" else "NOTIFICATIONS • NOT ENABLED"
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