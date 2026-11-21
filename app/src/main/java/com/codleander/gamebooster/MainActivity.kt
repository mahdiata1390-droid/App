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
import android.media.SoundPool
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager.LayoutParams
import androidx.tracing.Trace
import java.util.Locale
import kotlin.math.roundToInt
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
    private lateinit var writeSettingsState: TextView
    private var sessionStartedAt = 0L
    private var selectedProfile = Profile()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var sounds: SoundPool? = null
    private var boostSound = 0
    private var tapSound = 0
    private var successSound = 0
    private var errorSound = 0
    private var dashboardRunning = false
    private lateinit var homePage: View
    private lateinit var gamesPage: View
    private lateinit var boostingPage: View
    private lateinit var settingsPage: View
    private lateinit var permissionPage: View
    private lateinit var historyPage: View
    private lateinit var aboutPage: View

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
        writeSettingsState = findViewById(R.id.writeSettingsPermissionState)
        homePage = findViewById(R.id.homePage)
        gamesPage = findViewById(R.id.gamesPage)
        boostingPage = findViewById(R.id.boostingPage)
        settingsPage = findViewById(R.id.settingsPage)
        permissionPage = findViewById(R.id.permissionPage)
        historyPage = findViewById(R.id.historyPage)
        aboutPage = findViewById(R.id.aboutPage)
        setupNavigation()
        applyGlobalUiPolish()
        recoverStaleRuntimeIfNeeded()
        startLiveDashboard()
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
        sounds = SoundPool.Builder().setMaxStreams(3).build().also {
            boostSound = loadRawSound(it, "sfx_boost")
            tapSound = loadRawSound(it, "sfx_tap")
            successSound = loadRawSound(it, "sfx_success")
            errorSound = loadRawSound(it, "sfx_error")
        }

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
        findViewById<Button>(R.id.writeSettingsPermission).setOnClickListener { pulse(it); feedback(false); openWriteSettings() }
        findViewById<Button>(R.id.boostBack).setOnClickListener { pulse(it); showPage(homePage) }
        findViewById<Button>(R.id.endBoost).setOnClickListener { pulse(it); stopService(Intent(this, BoosterService::class.java)); showPage(homePage); showLastSessionOrReady() }
        findViewById<Button>(R.id.permissionButton).setOnClickListener { pulse(it); showPage(permissionPage); updatePermissionStates() }
        findViewById<TextView>(R.id.homePremium).setOnClickListener { pulse(it); showPage(aboutPage) }
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (::dndState.isInitialized) {
            recoverStaleRuntimeIfNeeded()
            syncActiveSessionStart()
            updatePermissionStates()
            startLiveDashboard()
        }
    }

    override fun onPause() {
        stopLiveDashboard()
        super.onPause()
    }

    override fun onDestroy() {
        stopLiveDashboard()
        uiHandler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        sounds?.release()
        sounds = null
        super.onDestroy()
    }

    private val dashboardRunnable = object : Runnable {
        override fun run() {
            if (!dashboardRunning) return
            updateStats()
            updateNetwork()
            updatePing()
            updateSessionTimer()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private fun startLiveDashboard() {
        if (dashboardRunning) return
        dashboardRunning = true
        uiHandler.removeCallbacks(dashboardRunnable)
        uiHandler.post(dashboardRunnable)
    }

    private fun stopLiveDashboard() {
        dashboardRunning = false
        uiHandler.removeCallbacks(dashboardRunnable)
    }

    private fun applyGlobalUiPolish() {
        // Do not keep the booster window awake unconditionally.
    }

    private fun setupNavigation() {
        findViewById<View>(R.id.navHome).setOnClickListener { showPage(homePage) }
        findViewById<View>(R.id.navGames).setOnClickListener { showPage(gamesPage); loadGames() }
        findViewById<View>(R.id.navHistory).setOnClickListener { showPage(historyPage); showHistorySummary() }
        findViewById<View>(R.id.navSettings).setOnClickListener { showPage(settingsPage) }
    }

    private fun showPage(page: View) {
        val pages = listOf(homePage, gamesPage, boostingPage, settingsPage, permissionPage, historyPage, aboutPage)
        pages.forEach { it.visibility = if (it === page) View.VISIBLE else View.GONE }
        findViewById<View>(R.id.bottomNav).visibility = if (page === boostingPage || page === aboutPage || page === permissionPage) View.GONE else View.VISIBLE
        val navHome = findViewById<TextView>(R.id.navHome)
        val navGames = findViewById<TextView>(R.id.navGames)
        val navHistory = findViewById<TextView>(R.id.navHistory)
        val navSettings = findViewById<TextView>(R.id.navSettings)
        val muted = Color.rgb(127, 166, 196)
        val active = Color.rgb(89, 216, 255)
        navHome.setTextColor(if (page === homePage) active else muted)
        navGames.setTextColor(if (page === gamesPage) active else muted)
        navHistory.setTextColor(if (page === historyPage) active else muted)
        navSettings.setTextColor(if (page === settingsPage) active else muted)
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
        val recoveredDuration = activePlayMs.coerceAtLeast(
            (now - start).coerceAtLeast(0L).coerceAtMost(2_000L)
        )

        var restored = true

        if (runtime.getBoolean("dnd_changed", false)) {
            val previous = runtime.getInt("previous_interruption_filter", -1)
            val expected = runtime.getInt(
                "expected_interruption_filter",
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (previous >= 0) {
                if (!nm.isNotificationPolicyAccessGranted) {
                    restored = false
                } else if (nm.currentInterruptionFilter == expected) {
                    val ok = runCatching {
                        nm.setInterruptionFilter(previous)
                        nm.currentInterruptionFilter == previous
                    }.getOrDefault(false)
                    restored = restored && ok
                }
            }
        }

        if (runtime.getBoolean("screen_timeout_changed", false)) {
            if (!Settings.System.canWrite(this)) {
                restored = false
            } else {
                val previous = runtime.getLong("previous_screen_timeout", -1L)
                val current = runCatching {
                    Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
                }.getOrNull()
                val applied = runtime.getLong("applied_screen_timeout", 30L * 60L * 1000L)
                if (SessionGuards.shouldRestoreLong(true, previous, current, applied)) {
                    val ok = runCatching {
                        Settings.System.putLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, previous)
                        Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) == previous
                    }.getOrDefault(false)
                    restored = restored && ok
                }
            }
        }

        if (runtime.getBoolean("brightness_changed", false)) {
            if (!Settings.System.canWrite(this)) {
                restored = false
            } else {
                val previous = runtime.getInt("previous_brightness", -1)
                val current = runCatching {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }.getOrNull()
                if (SessionGuards.shouldRestoreInt(true, previous, current, previous)) {
                    val ok = runCatching {
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, previous)
                        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) == previous
                    }.getOrDefault(false)
                    restored = restored && ok
                }
            }
        }

        if (!restored) {
            statusView.text = "RECOVERY PENDING • ENABLE REQUIRED ACCESS TO RESTORE SETTINGS"
            return
        }

        if (!target.isNullOrBlank() && activePlayMs > 0L) {
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
            val targets = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
            var bestMs: Long? = null

            for (host in targets) {
                val ms = runCatching {
                    val address = java.net.InetAddress.getByName(host)
                    val start = System.nanoTime()
                    if (!address.isReachable(1200)) return@runCatching null
                    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                }.getOrNull()

                if (ms != null && (bestMs == null || ms < bestMs!!)) {
                    bestMs = ms
                }
            }

            // ICMP reachability is not guaranteed on every Android/network path.
            // If it is blocked, use a short TCP connect as a latency fallback
            // rather than reporting a fake value.
            if (bestMs == null) {
                bestMs = runCatching {
                    val start = System.nanoTime()
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress("1.1.1.1", 443), 1200)
                    }
                    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                }.getOrNull()
            }

            val result = bestMs
            runOnUiThread {
                if (isFinishing || isDestroyedCompat()) return@runOnUiThread
                pingView.text = if (result != null) {
                    "PING\n${result} ms"
                } else {
                    "PING\nTIMEOUT"
                }
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
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "NO INTERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
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

    private fun loadRawSound(pool: SoundPool, name: String): Int {
        val id = resources.getIdentifier(name, "raw", packageName)
        return if (id != 0) pool.load(this, id, 1) else 0
    }

    private fun feedback(strong: Boolean) {
        playSound(if (strong) boostSound else tapSound, if (strong) 1.0f else 1.08f, 0.82f)
        tone?.startTone(if (strong) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 90)
        vibrate(if (strong) 35L else 20L)
    }

    private fun playSound(soundId: Int, rate: Float = 1f, volume: Float = 0.82f) {
        if (soundId != 0) sounds?.play(soundId, volume, volume, 1, 0, rate)
    }

    private fun vibrate(duration: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        else { @Suppress("DEPRECATION") vibrator.vibrate(duration) }
    }

    private fun successFeedback() {
        playSound(successSound, 1.12f, 0.9f)
        vibrate(45L)
    }

    private fun errorFeedback() {
        playSound(errorSound, 0.82f, 0.72f)
        vibrate(70L)
    }

    private fun pulse(view: View) {
        view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_electric))
    }

    private fun updateStats() {
        Trace.beginSection("GameBooster.updateStats")
        try {
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
        } finally {
            Trace.endSection()
        }
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
        Trace.beginSection("GameBooster.loadGames")
        try {
        val pm = packageManager
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()
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
                setPadding(dp(18), dp(12), dp(10), dp(12))
                background = getDrawable(R.drawable.neo_card)
                elevation = 6f
            }
            val icon = ImageView(this).apply {
                setImageDrawable(ri.loadIcon(pm))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
            val nameBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
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
                for (i in 0 until gamesContainer.childCount) {
                    gamesContainer.getChildAt(i).background = getDrawable(R.drawable.neo_card)
                }
                row.background = getDrawable(R.drawable.neo_selected)
                selectedPackage = ri.activityInfo.packageName
                selectedLabel = ri.loadLabel(pm).toString()
                selectedProfile = loadProfile(selectedPackage!!)
                pulse(row)
                playSound(tapSound, 1.12f, 0.7f)
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
            lp.bottomMargin = dp(12)
            gamesContainer.addView(row, lp)
        }
        } finally {
            Trace.endSection()
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
        showPage(boostingPage)

        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            errorFeedback()
            pulse(boostView)
            statusView.text = "GAME COULD NOT BE LAUNCHED • $label"
            return
        }

        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        val activePackage = runtime.getString("target_package", null)
        val activeStart = runtime.getLong("session_started_at", 0L)
        val alreadyActive = activeStart > 0L && activePackage == pkg
        val prefs = getSharedPreferences("game_profiles", MODE_PRIVATE)

        // Launch first so a failed launch can never leave an orphan monitoring service.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
        } catch (e: RuntimeException) {
            errorFeedback()
            statusView.text = "GAME COULD NOT BE LAUNCHED • $label"
            sessionStartedAt = 0L
            return
        }

        if (alreadyActive) {
            sessionStartedAt = activeStart
            statusView.text = "BOOST ALREADY ACTIVE • ${label.uppercase(Locale.ROOT)}"
            return
        }

        if (!hasUsageAccess()) {
            sessionStartedAt = 0L
            statusView.text = "GAME LAUNCHED • ENABLE USAGE ACCESS FOR BOOST"
            return
        }

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
            prefs.edit()
                .putString("${pkg}_label", label)
                .putInt("${pkg}_launches", prefs.getInt("${pkg}_launches", 0) + 1)
                .apply()
            successFeedback()
            pulse(boostView)
            pulse(lightningIcon)
            statusView.text = "BOOST STARTING • ${label.uppercase(Locale.ROOT)}"
        } catch (e: SecurityException) {
            errorFeedback()
            sessionStartedAt = 0L
            statusView.text = "GAME RUNNING • BOOST SERVICE PERMISSION BLOCKED"
        } catch (e: RuntimeException) {
            errorFeedback()
            sessionStartedAt = 0L
            statusView.text = "GAME RUNNING • BOOST SERVICE COULD NOT START"
        }
    }
    private fun openDndSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            .onFailure { statusView.text = "DND SETTINGS ARE NOT AVAILABLE ON THIS DEVICE" }
    }

    private fun openUsageSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .onFailure { statusView.text = "USAGE ACCESS SETTINGS ARE NOT AVAILABLE" }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }.onFailure { statusView.text = "OVERLAY SETTINGS ARE NOT AVAILABLE ON THIS DEVICE" }
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
        writeSettingsState.text = if (Settings.System.canWrite(this)) "SYSTEM SETTINGS • ENABLED" else "SYSTEM SETTINGS • NOT ENABLED"
    }

    private fun openWriteSettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
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