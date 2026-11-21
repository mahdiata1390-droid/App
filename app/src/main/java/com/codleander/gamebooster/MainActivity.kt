package com.codleander.gamebooster

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val PAGE_HOME = "home"
        private const val PAGE_LIBRARY = "library"
        private const val PAGE_BOOSTING = "boosting"
        private const val PAGE_SETTINGS = "settings"
        private const val PAGE_PERMISSIONS = "permissions"
        private const val PAGE_HISTORY = "history"
        private const val PAGE_ABOUT = "about"

        private const val FILTER_ALL = 0
        private const val FILTER_POPULAR = 1
        private const val FILTER_INSTALLED = 2

        private const val STEP_PENDING = 0
        private const val STEP_ACTIVE = 1
        private const val STEP_DONE = 2

        private const val REQ_NOTIFICATIONS = 1001
    }

    private val ui = Handler(Looper.getMainLooper())
    private val games = mutableListOf<GameInfo>()

    private var currentPage = PAGE_HOME
    private var selectedGame: GameInfo? = null
    private var libraryFilter = FILTER_ALL
    private var gamesLoading = false
    private var boosting = false
    private var boostCancelled = false
    private var statsRunning = false
    private var pingInFlight = false
    private var lastPingAt = 0L
    private var historyRangeDays = 30
    private var syncingToggles = false

    private var sounds: SoundPool? = null
    private var tapSound = 0
    private var successSound = 0

    private val navPages = listOf(PAGE_HOME, PAGE_LIBRARY, PAGE_HISTORY, PAGE_SETTINGS)
    private val navIconIds = listOf(R.id.navHomeIcon, R.id.navGamesIcon, R.id.navHistoryIcon, R.id.navSettingsIcon)
    private val navLabelIds = listOf(R.id.navHomeLabel, R.id.navGamesLabel, R.id.navHistoryLabel, R.id.navSettingsLabel)

    private val stepRowIds = listOf(R.id.boostStepRow1, R.id.boostStepRow2, R.id.boostStepRow3, R.id.boostStepRow4)
    private val stepDotIds = listOf(R.id.boostStepDot1, R.id.boostStepDot2, R.id.boostStepDot3, R.id.boostStepDot4)
    private val stepIconIds = listOf(R.id.boostStepIcon1, R.id.boostStepIcon2, R.id.boostStepIcon3, R.id.boostStepIcon4)
    private val stepTextIds = listOf(R.id.boostStepText1, R.id.boostStepText2, R.id.boostStepText3, R.id.boostStepText4)

    override fun attachBaseContext(newBase: Context) {
        val language = newBase.getSharedPreferences("booster_settings", Context.MODE_PRIVATE)
            .getString("language", "en") ?: "en"
        val locale = Locale(language)
        Locale.setDefault(locale)
        val configuration = Configuration(newBase.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initSounds()
        applyAppearance()
        bindNavigation()
        bindHome()
        bindLibrary()
        bindBoosting()
        bindSettings()
        bindPermissions()
        bindHistory()
        bindAbout()

        findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.version_label, BuildConfig.VERSION_NAME)

        showPage(PAGE_HOME)
        loadGames()
        startSplash()
    }

    override fun onResume() {
        super.onResume()
        startStatsLoop()
        updateHomeChip()
        refreshHomeStatus()
        refreshPermissions()
        syncToggles()
        if (currentPage == PAGE_HISTORY) refreshHistory()
    }

    override fun onPause() {
        stopStatsLoop()
        super.onPause()
    }

    override fun onDestroy() {
        stopStatsLoop()
        ui.removeCallbacksAndMessages(null)
        sounds?.release()
        sounds = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentPage != PAGE_HOME) {
            abortBoost()
            showPage(PAGE_HOME)
            return
        }
        super.onBackPressed()
    }

    // ---------------------------------------------------------------- navigation

    private fun bindNavigation() {
        findViewById<View>(R.id.navHome).setOnClickListener { showPage(PAGE_HOME) }
        findViewById<View>(R.id.navGames).setOnClickListener { showPage(PAGE_LIBRARY) }
        findViewById<View>(R.id.navHistory).setOnClickListener { showPage(PAGE_HISTORY) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showPage(PAGE_SETTINGS) }
        findViewById<View>(R.id.menuButton).setOnClickListener { showPage(PAGE_SETTINGS) }
        findViewById<View>(R.id.crownButton).setOnClickListener { showPage(PAGE_ABOUT) }
    }

    private fun showPage(page: String) {
        currentPage = page
        val ids = mapOf(
            PAGE_HOME to R.id.homePage,
            PAGE_LIBRARY to R.id.gamesPage,
            PAGE_BOOSTING to R.id.boostingPage,
            PAGE_SETTINGS to R.id.settingsPage,
            PAGE_PERMISSIONS to R.id.permissionPage,
            PAGE_HISTORY to R.id.historyPage,
            PAGE_ABOUT to R.id.aboutPage
        )
        ids.forEach { (key, id) ->
            findViewById<View>(id).visibility = if (key == page) View.VISIBLE else View.GONE
        }

        val navVisible = navPages.contains(page)
        findViewById<View>(R.id.bottomNav).visibility = if (navVisible) View.VISIBLE else View.GONE
        val host = findViewById<View>(R.id.pageHost)
        (host.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.bottomMargin = if (navVisible) dp(76) else 0
            host.layoutParams = params
        }
        updateNav(page)

        when (page) {
            PAGE_HOME -> {
                updateHomeChip()
                refreshHomeStatus()
            }
            PAGE_LIBRARY -> renderGames()
            PAGE_HISTORY -> refreshHistory()
            PAGE_PERMISSIONS -> refreshPermissions()
        }
    }

    private fun updateNav(page: String) {
        for (index in navPages.indices) {
            val selected = navPages[index] == page
            val color = ContextCompat.getColor(
                this,
                if (selected) R.color.gb_accent_bright else R.color.gb_muted
            )
            findViewById<ImageView>(navIconIds[index]).setColorFilter(color)
            findViewById<TextView>(navLabelIds[index]).setTextColor(color)
        }
    }

    // --------------------------------------------------------------------- home

    private fun bindHome() {
        findViewById<View>(R.id.homeBoostButton).setOnClickListener {
            feedback(true)
            startBoost(selectedGame)
        }
        findViewById<View>(R.id.homeLibrary).setOnClickListener { showPage(PAGE_LIBRARY) }
        findViewById<View>(R.id.homeGameChip).setOnClickListener { showPage(PAGE_LIBRARY) }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!statsRunning) return
            refreshDeviceStats()
            refreshPing()
            updateHomeChip()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun startStatsLoop() {
        if (statsRunning) return
        statsRunning = true
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
    }

    private fun stopStatsLoop() {
        statsRunning = false
        ui.removeCallbacks(statsTick)
    }

    private fun refreshDeviceStats() {
        val memory = DeviceStats.memory(this)
        findViewById<TextView>(R.id.statRamValue).text =
            getString(R.string.percent_value, memory.usedPercent)
        findViewById<TextView>(R.id.statRamSub).text = getString(
            R.string.ram_usage,
            memory.usedBytes / 1073741824.0,
            memory.totalBytes / 1073741824.0
        )
        findViewById<ProgressBar>(R.id.statRamBar).progress = memory.usedPercent

        val battery = DeviceStats.battery(this)
        if (battery.level >= 0) {
            findViewById<TextView>(R.id.statBatteryValue).text =
                getString(R.string.percent_value, battery.level)
            findViewById<TextView>(R.id.statBatterySub).text = getString(
                if (battery.charging) R.string.charging else R.string.on_battery
            )
            findViewById<ProgressBar>(R.id.statBatteryBar).progress = battery.level
        } else {
            findViewById<TextView>(R.id.statBatteryValue).text = getString(R.string.gain_none)
            findViewById<TextView>(R.id.statBatterySub).text = getString(R.string.not_available)
            findViewById<ProgressBar>(R.id.statBatteryBar).progress = 0
        }

        val temperature = battery.temperatureC
        val thermal = DeviceStats.thermalStatus(this)
        if (temperature != null) {
            findViewById<TextView>(R.id.statTempValue).text =
                getString(R.string.temp_celsius, temperature)
            findViewById<ProgressBar>(R.id.statTempBar).progress =
                (((temperature - 20f) / 45f) * 100f).toInt().coerceIn(0, 100)
        } else {
            findViewById<TextView>(R.id.statTempValue).text = getString(R.string.gain_none)
            findViewById<ProgressBar>(R.id.statTempBar).progress = 0
        }
        findViewById<TextView>(R.id.statTempSub).text =
            getString(DeviceStats.thermalLabelRes(thermal))
        findViewById<TextView>(R.id.statNetworkSub).text =
            getString(DeviceStats.networkLabelRes(this))
    }

    private fun refreshPing() {
        val now = System.currentTimeMillis()
        if (pingInFlight || now - lastPingAt < 8000L) return
        pingInFlight = true
        lastPingAt = now
        Thread {
            val latency = DeviceStats.pingMs()
            ui.post {
                pingInFlight = false
                if (isFinishing || isDestroyed) return@post
                if (latency != null) {
                    findViewById<TextView>(R.id.statNetworkValue).text =
                        getString(R.string.ms_value, latency)
                    findViewById<ProgressBar>(R.id.statNetworkBar).progress =
                        latency.toInt().coerceIn(0, 200)
                } else {
                    findViewById<TextView>(R.id.statNetworkValue).text =
                        getString(R.string.timeout)
                    findViewById<ProgressBar>(R.id.statNetworkBar).progress = 0
                }
            }
        }.start()
    }

    private fun updateHomeChip() {
        val chip = findViewById<View>(R.id.homeGameChip)
        val name = findViewById<TextView>(R.id.homeGameName)
        val state = findViewById<TextView>(R.id.homeGameState)
        val icon = findViewById<ImageView>(R.id.homeGameIcon)

        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        val activeLabel = runtime.getString("target_label", null)
        val startedAt = runtime.getLong("session_started_at", 0L)

        when {
            !activeLabel.isNullOrBlank() && startedAt > 0L -> {
                chip.visibility = View.VISIBLE
                name.text = activeLabel
                state.text = getString(
                    R.string.session_running,
                    formatDuration(System.currentTimeMillis() - startedAt)
                )
                showAppIcon(icon, runtime.getString("target_package", null))
            }
            selectedGame != null -> {
                val game = selectedGame!!
                chip.visibility = View.VISIBLE
                name.text = game.label
                state.text = getString(
                    if (game.installed) R.string.ready_to_boost else R.string.game_not_installed
                )
                showAppIcon(icon, game.packageName)
            }
            else -> chip.visibility = View.GONE
        }
    }

    private fun showAppIcon(view: ImageView, packageName: String?) {
        val drawable = if (packageName == null) null else runCatching {
            packageManager.getApplicationIcon(packageName)
        }.getOrNull()
        if (drawable != null) {
            view.setImageDrawable(drawable)
            view.clearColorFilter()
        } else {
            view.setImageResource(R.drawable.ic_gamepad)
            view.setColorFilter(ContextCompat.getColor(this, R.color.gb_accent_bright))
        }
    }

    private fun refreshHomeStatus() {
        val status = findViewById<TextView>(R.id.homeStatus)
        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        val active = runtime.getString("target_label", null)
        val game = selectedGame
        when {
            !active.isNullOrBlank() -> status.text = getString(R.string.status_active_short, active)
            game != null -> status.text = getString(R.string.status_selected, game.label)
            else -> status.setText(R.string.status_ready)
        }
    }

    // ------------------------------------------------------------------ library

    private fun bindLibrary() {
        findViewById<View>(R.id.filterAll).setOnClickListener { setFilter(FILTER_ALL) }
        findViewById<View>(R.id.filterPopular).setOnClickListener { setFilter(FILTER_POPULAR) }
        findViewById<View>(R.id.filterInstalled).setOnClickListener { setFilter(FILTER_INSTALLED) }

        findViewById<View>(R.id.gamesSearchToggle).setOnClickListener {
            val search = findViewById<EditText>(R.id.gamesSearch)
            if (search.visibility == View.VISIBLE) {
                search.setText("")
                search.visibility = View.GONE
            } else {
                search.visibility = View.VISIBLE
                search.requestFocus()
            }
        }
        findViewById<EditText>(R.id.gamesSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderGames()
        })
    }

    private fun loadGames() {
        if (gamesLoading) return
        gamesLoading = true
        Thread {
            val loaded = runCatching { GameLibrary.load(this) }.getOrDefault(emptyList())
            ui.post {
                gamesLoading = false
                games.clear()
                games.addAll(loaded)
                renderGames()
            }
        }.start()
    }

    private fun setFilter(filter: Int) {
        libraryFilter = filter
        val chipIds = listOf(R.id.filterAll, R.id.filterPopular, R.id.filterInstalled)
        chipIds.forEachIndexed { index, id ->
            val view = findViewById<TextView>(id)
            val selected = index == filter
            view.background = ContextCompat.getDrawable(
                this,
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            view.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.gb_text else R.color.gb_muted)
            )
        }
        renderGames()
    }

    private fun renderGames() {
        val grid = findViewById<GridLayout>(R.id.gamesGrid)
        grid.removeAllViews()
        val query = findViewById<EditText>(R.id.gamesSearch).text.toString().trim().lowercase(Locale.ROOT)
        val filtered = games.filter { game ->
            val matchesFilter = when (libraryFilter) {
                FILTER_POPULAR -> GameLibrary.isPopular(game.packageName)
                FILTER_INSTALLED -> game.installed
                else -> true
            }
            val matchesQuery = query.isEmpty() || game.label.lowercase(Locale.ROOT).contains(query)
            matchesFilter && matchesQuery
        }
        findViewById<View>(R.id.gamesEmpty).visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
        filtered.forEach { grid.addView(buildGameCard(it), gridParams()) }
    }

    private fun gridParams(): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        width = 0
        height = GridLayout.LayoutParams.WRAP_CONTENT
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
        setMargins(dp(4), dp(4), dp(4), dp(6))
    }

    private fun buildGameCard(game: GameInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_soft)
            setPadding(dp(12), dp(12), dp(12), dp(10))
            isClickable = true
            isFocusable = true
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (game.icon != null) {
                setImageDrawable(game.icon)
            } else {
                setImageResource(R.drawable.ic_gamepad)
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.gb_accent_bright))
                alpha = 0.75f
            }
            contentDescription = game.label
        }

        val title = TextView(this).apply {
            text = game.label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gb_text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
            ).apply { topMargin = dp(6) }
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)
            )
        }
        val action = TextView(this).apply {
            setText(if (game.installed) R.string.boost_game else R.string.install_game)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (game.installed) R.color.gb_accent_bright else R.color.gb_muted
                )
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val plus = ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.gb_accent_bright))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_circle)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            contentDescription = getString(R.string.boost_game)
        }
        footer.addView(action)
        footer.addView(plus)

        card.addView(icon)
        card.addView(title)
        card.addView(footer)
        card.setOnClickListener { onGameClicked(game) }
        plus.setOnClickListener { onGameClicked(game) }
        return card
    }

    private fun onGameClicked(game: GameInfo) {
        if (!game.installed) {
            openStore(game.packageName)
            return
        }
        selectedGame = game
        playSound(tapSound, 0.7f)
        updateHomeChip()
        refreshHomeStatus()
        if (Prefs.autoBoost(this)) {
            startBoost(game)
        } else {
            showPage(PAGE_HOME)
        }
    }

    private fun openStore(packageName: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        )
        val opened = runCatching { startActivity(market) }.isSuccess ||
            runCatching { startActivity(web) }.isSuccess
        if (!opened) toast(getString(R.string.store_unavailable))
    }

    // ----------------------------------------------------------------- boosting

    private fun bindBoosting() {
        findViewById<View>(R.id.boostBack).setOnClickListener { abortBoost() }
        findViewById<View>(R.id.boostCancel).setOnClickListener { abortBoost() }
        findViewById<View>(R.id.boostSettings).setOnClickListener {
            abortBoost()
            showPage(PAGE_SETTINGS)
        }
    }

    private fun startBoost(game: GameInfo?) {
        if (boosting) return
        boosting = true
        boostCancelled = false
        selectedGame = game ?: selectedGame
        showPage(PAGE_BOOSTING)
        feedback(true)

        val ring = findViewById<BoostRingView>(R.id.boostRing)
        ring.setProgressNow(0f)
        ring.animateTo(1f, 3400L)
        findViewById<TextView>(R.id.boostPercent).text = getString(R.string.boost_percent, 0)
        findViewById<TextView>(R.id.boostStage).setText(R.string.optimizing_device)
        for (index in 0 until 4) setStepState(index, STEP_PENDING)

        val mode = Prefs.boostMode(this)
        val target = game ?: selectedGame
        val startedAt = System.currentTimeMillis()

        Thread {
            var freedMb = 0
            var thermal = 0
            try {
                val before = DeviceStats.availableMb(this)
                for (step in 0 until 4) {
                    if (boostCancelled) return@Thread
                    ui.post {
                        if (!boostCancelled) {
                            setStepState(step, STEP_ACTIVE)
                            findViewById<TextView>(R.id.boostStage).setText(stepTitleRes(step))
                        }
                    }
                    when (step) {
                        0 -> clearBackgroundApps(mode)
                        1 -> System.gc()
                        2 -> if (target == null) applyDndForDeviceBoost()
                        3 -> thermal = DeviceStats.thermalStatus(this)
                    }
                    Thread.sleep(700L)
                    if (boostCancelled) return@Thread
                    ui.post {
                        if (!boostCancelled) {
                            setStepState(step, STEP_DONE)
                            findViewById<TextView>(R.id.boostPercent).text =
                                getString(R.string.boost_percent, (step + 1) * 25)
                        }
                    }
                }
                freedMb = (DeviceStats.availableMb(this) - before).coerceAtLeast(0L).toInt()
                if (!boostCancelled) ui.post { finishBoost(target, freedMb, thermal, startedAt) }
            } catch (t: Throwable) {
                if (!boostCancelled) ui.post { finishBoost(target, 0, 0, startedAt) }
            }
        }.start()
    }

    private fun stepTitleRes(step: Int): Int = when (step) {
        0 -> R.string.step_clean
        1 -> R.string.step_ram
        2 -> R.string.step_optimize
        else -> R.string.step_cool
    }

    private fun setStepState(index: Int, state: Int) {
        val dot = findViewById<FrameLayout>(stepDotIds[index])
        val icon = findViewById<ImageView>(stepIconIds[index])
        val text = findViewById<TextView>(stepTextIds[index])
        when (state) {
            STEP_DONE -> {
                dot.setBackgroundResource(R.drawable.bg_step_circle_done)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.gb_green))
                text.setTextColor(ContextCompat.getColor(this, R.color.gb_text))
            }
            STEP_ACTIVE -> {
                dot.setBackgroundResource(R.drawable.bg_step_circle)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.gb_accent_bright))
                text.setTextColor(ContextCompat.getColor(this, R.color.gb_accent_soft))
            }
            else -> {
                dot.setBackgroundResource(R.drawable.bg_step_circle)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.gb_step_pending))
                text.setTextColor(ContextCompat.getColor(this, R.color.gb_muted))
            }
        }
    }

    private fun clearBackgroundApps(mode: Int): Int {
        if (!hasUsageAccess()) return 0
        val limit = when (mode) {
            Prefs.MODE_PERFORMANCE -> 14
            Prefs.MODE_BATTERY -> 3
            else -> 7
        }
        val usageStats = runCatching {
            val manager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 6L * 60L * 60L * 1000L, now)
        }.getOrNull() ?: return 0

        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val launchers = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())

        var cleared = 0
        usageStats.sortedByDescending { it.lastTimeUsed }.forEach { stat ->
            if (cleared >= limit) return@forEach
            val pkg = stat.packageName
            if (pkg == packageName || pkg == "android" || launchers.contains(pkg)) return@forEach
            val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                ?: return@forEach
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem) return@forEach
            runCatching { activityManager.killBackgroundProcesses(pkg) }
            cleared++
        }
        return cleared
    }

    private fun applyDndForDeviceBoost() {
        if (!Prefs.gameMode(this)) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.isNotificationPolicyAccessGranted) return
        val current = manager.currentInterruptionFilter
        if (current == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
            current == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
            current == NotificationManager.INTERRUPTION_FILTER_NONE
        ) return
        getSharedPreferences("booster_runtime", MODE_PRIVATE).edit()
            .putInt("device_boost_dnd_previous", current)
            .apply()
        runCatching {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }

    private fun restoreDeviceBoostDnd() {
        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        if (!runtime.contains("device_boost_dnd_previous")) return
        val previous = runtime.getInt(
            "device_boost_dnd_previous", NotificationManager.INTERRUPTION_FILTER_ALL
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.isNotificationPolicyAccessGranted &&
            manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        ) {
            runCatching { manager.setInterruptionFilter(previous) }
        }
        runtime.edit().remove("device_boost_dnd_previous").apply()
    }

    private fun finishBoost(game: GameInfo?, freedMb: Int, thermal: Int, startedAt: Long) {
        boosting = false
        for (index in 0 until 4) setStepState(index, STEP_DONE)
        findViewById<BoostRingView>(R.id.boostRing).setProgressNow(1f)
        findViewById<TextView>(R.id.boostPercent).text = getString(R.string.boost_percent, 100)
        findViewById<TextView>(R.id.boostStage).text = getString(R.string.boost_done_title)

        val finishedAt = System.currentTimeMillis()
        HistoryStore.add(
            this,
            SessionRecord(
                start = startedAt,
                end = finishedAt,
                durationMs = finishedAt - startedAt,
                label = game?.label ?: getString(R.string.device_boost_label),
                packageName = game?.packageName,
                peakThermal = thermal,
                freedMb = freedMb,
                isBoost = true
            )
        )
        successFeedback()
        if (game != null) launchGameSession(game)
        restoreDeviceBoostDnd()

        ui.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            showPage(PAGE_HOME)
            findViewById<TextView>(R.id.homeStatus).text =
                getString(R.string.status_boost_freed, freedMb)
            refreshHistory()
        }, 900L)
    }

    private fun abortBoost() {
        if (boosting) {
            boosting = false
            boostCancelled = true
            restoreDeviceBoostDnd()
        }
        showPage(PAGE_HOME)
    }

    private fun launchGameSession(game: GameInfo) {
        val launch = packageManager.getLaunchIntentForPackage(game.packageName)
        if (launch == null) {
            toast(getString(R.string.launch_failed, game.label))
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(launch) }.isFailure) {
            toast(getString(R.string.launch_failed, game.label))
            return
        }
        val serviceIntent = Intent(this, BoosterService::class.java).apply {
            action = BoosterService.ACTION_START
            putExtra(BoosterService.EXTRA_PACKAGE, game.packageName)
            putExtra("profile_dnd", Prefs.gameMode(this@MainActivity))
            putExtra("profile_awake", true)
            putExtra("profile_brightness", false)
            putExtra("profile_hud", true)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        }
        selectedGame = game
    }

    // ------------------------------------------------------------------ settings

    private fun bindSettings() {
        findViewById<View>(R.id.rowBoostMode).setOnClickListener { chooseBoostMode() }
        findViewById<View>(R.id.rowAppearance).setOnClickListener { chooseAppearance() }
        findViewById<View>(R.id.rowLanguage).setOnClickListener { chooseLanguage() }
        findViewById<View>(R.id.rowPermissions).setOnClickListener { showPage(PAGE_PERMISSIONS) }

        val autoBoost = findViewById<Switch>(R.id.autoBoostSwitch)
        autoBoost.setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setAutoBoost(this, checked)
            playSound(tapSound, 0.6f)
        }
        val gameMode = findViewById<Switch>(R.id.gameModeSwitch)
        gameMode.setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setGameMode(this, checked)
            if (checked && !hasDndAccess()) toast(getString(R.string.game_mode_needs_access))
        }
        val notifications = findViewById<Switch>(R.id.notificationSwitch)
        notifications.setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setNotifications(this, checked)
            if (checked && !hasNotificationPermission()) requestNotificationPermission()
        }

        findViewById<TextView>(R.id.boostModeValue).setText(boostModeLabelRes(Prefs.boostMode(this)))
        findViewById<TextView>(R.id.appearanceValue).setText(
            if (Prefs.amoled(this)) R.string.appearance_amoled else R.string.appearance_dark
        )
        findViewById<TextView>(R.id.languageValue).text =
            if (Prefs.language(this) == "fa") "فارسی" else "English"
        syncToggles()
    }

    private fun syncToggles() {
        syncingToggles = true
        findViewById<Switch>(R.id.autoBoostSwitch).isChecked = Prefs.autoBoost(this)
        findViewById<Switch>(R.id.gameModeSwitch).isChecked = Prefs.gameMode(this)
        findViewById<Switch>(R.id.notificationSwitch).isChecked =
            Prefs.notifications(this) && hasNotificationPermission()
        syncingToggles = false
    }

    private fun boostModeLabelRes(mode: Int): Int = when (mode) {
        Prefs.MODE_PERFORMANCE -> R.string.boost_mode_performance
        Prefs.MODE_BATTERY -> R.string.boost_mode_battery
        else -> R.string.boost_mode_balanced
    }

    private fun chooseBoostMode() {
        val options = arrayOf(
            getString(R.string.boost_mode_performance),
            getString(R.string.boost_mode_balanced),
            getString(R.string.boost_mode_battery)
        )
        val selected = when (Prefs.boostMode(this)) {
            Prefs.MODE_PERFORMANCE -> 0
            Prefs.MODE_BATTERY -> 2
            else -> 1
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.boost_mode)
            .setSingleChoiceItems(options, selected) { dialog, which ->
                val mode = when (which) {
                    0 -> Prefs.MODE_PERFORMANCE
                    2 -> Prefs.MODE_BATTERY
                    else -> Prefs.MODE_BALANCED
                }
                Prefs.setBoostMode(this, mode)
                findViewById<TextView>(R.id.boostModeValue).setText(boostModeLabelRes(mode))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseAppearance() {
        val options = arrayOf(
            getString(R.string.appearance_dark),
            getString(R.string.appearance_amoled)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance)
            .setSingleChoiceItems(
                options,
                if (Prefs.amoled(this)) 1 else 0
            ) { dialog, which ->
                Prefs.setAmoled(this, which == 1)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseLanguage() {
        val languages = arrayOf("English", "فارسی")
        val current = if (Prefs.language(this) == "fa") 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(languages, current) { dialog, which ->
                Prefs.setLanguage(this, if (which == 1) "fa" else "en")
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyAppearance() {
        val amoled = Prefs.amoled(this)
        findViewById<View>(R.id.root).setBackgroundResource(
            if (amoled) R.drawable.bg_amoled else R.drawable.bg_app
        )
        findViewById<ImageView>(R.id.backdrop).alpha = if (amoled) 0.10f else 0.42f
    }

    // --------------------------------------------------------------- permissions

    private fun bindPermissions() {
        findViewById<View>(R.id.permissionsBack).setOnClickListener { showPage(PAGE_SETTINGS) }
        findViewById<View>(R.id.permissionsDone).setOnClickListener { showPage(PAGE_HOME) }
        findViewById<View>(R.id.permissionUsageRow).setOnClickListener { openUsageSettings() }
        findViewById<View>(R.id.permissionNotificationRow).setOnClickListener {
            requestNotificationPermission()
        }
        findViewById<View>(R.id.permissionBatteryRow).setOnClickListener { requestBatteryExemption() }
        findViewById<View>(R.id.permissionOverlayRow).setOnClickListener { openOverlaySettings() }
        findViewById<View>(R.id.permissionSettingsRow).setOnClickListener { openWriteSettings() }
    }

    private fun refreshPermissions() {
        val usage = hasUsageAccess()
        val notifications = hasNotificationPermission()
        val battery = isIgnoringBatteryOptimizations()
        val overlay = Settings.canDrawOverlays(this)
        val writeSettings = Settings.System.canWrite(this)

        bindPermissionStatus(R.id.permissionUsageStatus, usage)
        bindPermissionStatus(R.id.permissionNotificationStatus, notifications)
        bindPermissionStatus(R.id.permissionBatteryStatus, battery)
        bindPermissionStatus(R.id.permissionOverlayStatus, overlay)
        bindPermissionStatus(R.id.permissionSettingsStatus, writeSettings)

        val granted = listOf(usage, notifications, battery, overlay, writeSettings).count { it }
        val allGranted = granted == 5
        findViewById<TextView>(R.id.permissionHeadline).text = if (allGranted) {
            getString(R.string.perm_all_granted)
        } else {
            getString(R.string.perm_some_title, granted, 5)
        }
        findViewById<TextView>(R.id.permissionSubline).setText(
            if (allGranted) R.string.perm_all_granted_sub else R.string.perm_some_sub
        )
        findViewById<TextView>(R.id.permissionsValue).text =
            getString(R.string.perm_some_title, granted, 5)
    }

    private fun bindPermissionStatus(id: Int, granted: Boolean) {
        val view = findViewById<TextView>(id)
        view.setText(if (granted) R.string.granted else R.string.grant)
        view.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.gb_green else R.color.gb_accent_bright)
        )
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }.onFailure { toast(getString(R.string.settings_unavailable)) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            refreshPermissions()
            syncToggles()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        return manager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            return
        }
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        if (runCatching { startActivity(request) }.isFailure) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast(getString(R.string.settings_unavailable)) }
        }
    }

    private fun openUsageSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .onFailure { toast(getString(R.string.settings_unavailable)) }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { toast(getString(R.string.settings_unavailable)) }
    }

    private fun openWriteSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { toast(getString(R.string.settings_unavailable)) }
    }

    private fun hasDndAccess(): Boolean {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return manager.isNotificationPolicyAccessGranted
    }

    // ------------------------------------------------------------------ history

    private fun bindHistory() {
        findViewById<View>(R.id.historyRangeDay).setOnClickListener { setHistoryRange(1) }
        findViewById<View>(R.id.historyRangeWeek).setOnClickListener { setHistoryRange(7) }
        findViewById<View>(R.id.historyRangeMonth).setOnClickListener { setHistoryRange(30) }
    }

    private fun setHistoryRange(days: Int) {
        historyRangeDays = days
        refreshHistory()
    }

    private fun refreshHistory() {
        styleRangeTab(R.id.historyRangeDay, historyRangeDays == 1)
        styleRangeTab(R.id.historyRangeWeek, historyRangeDays == 7)
        styleRangeTab(R.id.historyRangeMonth, historyRangeDays == 30)

        val now = System.currentTimeMillis()
        val span = historyRangeDays * 24L * 60L * 60L * 1000L
        val since = now - span
        val bucketCount = when (historyRangeDays) {
            1 -> 12
            7 -> 7
            else -> 10
        }
        val values = HistoryStore.buckets(this, since, now, bucketCount)
        val labels = buildLabels(historyRangeDays, bucketCount)
        findViewById<HistoryChartView>(R.id.historyChart).setData(values, labels)
        findViewById<View>(R.id.historyEmpty).visibility =
            if (values.sum() <= 0f) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.historyTotalBoosts).text =
            HistoryStore.boostsInRange(this, since, now).toString()
        val average = HistoryStore.averageFreedMb(this, since, now)
        findViewById<TextView>(R.id.historyAvgGain).text =
            if (average > 0) getString(R.string.mb_freed, average) else getString(R.string.gain_none)
        findViewById<TextView>(R.id.historyPlayTime).text =
            formatPlayTime(HistoryStore.playMsInRange(this, since, now))

        val currentBoosts = HistoryStore.boostsInRange(this, since, now)
        val previousBoosts = HistoryStore.boostsInRange(this, since - span, since - 1)
        findViewById<TextView>(R.id.historyGainChip).text =
            if (previousBoosts > 0 && currentBoosts > previousBoosts) {
                getString(
                    R.string.gain_positive,
                    ((currentBoosts - previousBoosts) * 100) / previousBoosts
                )
            } else {
                getString(R.string.gain_none)
            }

        renderRecentActivity()
    }

    private fun styleRangeTab(id: Int, selected: Boolean) {
        val view = findViewById<TextView>(id)
        view.background = if (selected) {
            ContextCompat.getDrawable(this, R.drawable.bg_tab_selected)
        } else {
            null
        }
        view.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.gb_text else R.color.gb_muted)
        )
    }

    private fun buildLabels(days: Int, buckets: Int): List<String> {
        val today = java.time.LocalDate.now()
        return when (days) {
            1 -> (0 until buckets).map { index ->
                String.format(Locale.US, "%02d", (index * 2) % 24)
            }
            7 -> (0 until buckets).map { index ->
                today.minusDays((buckets - 1 - index).toLong())
                    .dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            }
            else -> (0 until buckets).map { index ->
                today.minusDays(((buckets - 1 - index) * 3).toLong()).dayOfMonth.toString()
            }
        }
    }

    private fun renderRecentActivity() {
        val container = findViewById<LinearLayout>(R.id.historyRecent)
        container.removeAllViews()
        val recent = HistoryStore.recent(this, 5)
        if (recent.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_activity)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gb_muted))
                setPadding(0, dp(12), 0, dp(12))
            }
            container.addView(empty)
            return
        }
        recent.forEach { container.addView(buildActivityRow(it)) }
    }

    private fun buildActivityRow(record: SessionRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card_soft)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            val drawable: Drawable? = record.packageName?.let { pkg ->
                runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
            }
            if (drawable != null) {
                setImageDrawable(drawable)
            } else {
                setImageResource(R.drawable.ic_bolt)
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.gb_accent_bright))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_circle)
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(8), 0)
        }
        val title = TextView(this).apply {
            text = record.label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gb_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle = TextView(this).apply {
            val action = getString(
                if (record.isBoost) R.string.boost_completed else R.string.session_completed
            )
            text = "$action • ${relativeTime(record.start)}"
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gb_muted))
        }
        texts.addView(title)
        texts.addView(subtitle)

        val value = TextView(this).apply {
            text = if (record.isBoost && record.freedMb > 0) {
                getString(R.string.mb_freed, record.freedMb)
            } else if (record.durationMs > 0) {
                formatPlayTime(record.durationMs)
            } else {
                getString(R.string.gain_none)
            }
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (record.isBoost) R.color.gb_green else R.color.gb_accent_bright
                )
            )
        }

        row.addView(icon)
        row.addView(texts)
        row.addView(value)
        return row
    }

    private fun relativeTime(timestamp: Long): String {
        val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minutes = delta / 60000L
        return when {
            minutes < 1 -> getString(R.string.ago_now)
            minutes < 60 -> getString(R.string.ago_minutes, minutes.toInt())
            minutes < 1440 -> getString(R.string.ago_hours, (minutes / 60).toInt())
            else -> getString(R.string.ago_days, (minutes / 1440).toInt())
        }
    }

    private fun formatPlayTime(milliseconds: Long): String {
        val minutes = milliseconds / 60000L
        return if (minutes >= 60) {
            getString(R.string.hours_short, (minutes / 60).toInt())
        } else {
            getString(R.string.minutes_short, minutes.toInt())
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        val total = (milliseconds / 1000L).coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            total / 3600,
            (total % 3600) / 60,
            total % 60
        )
    }

    // -------------------------------------------------------------------- about

    private fun bindAbout() {
        findViewById<View>(R.id.aboutBack).setOnClickListener { showPage(PAGE_HOME) }
    }

    // ------------------------------------------------------------------ feedback

    private fun initSounds() {
        sounds = runCatching {
            SoundPool.Builder().setMaxStreams(2).build().also { pool ->
                tapSound = pool.load(this, R.raw.sfx_tap, 1)
                successSound = pool.load(this, R.raw.sfx_success, 1)
            }
        }.getOrNull()
    }

    private fun playSound(id: Int, volume: Float = 0.85f) {
        if (id != 0) sounds?.play(id, volume, volume, 1, 0, 1f)
    }

    private fun feedback(strong: Boolean) {
        playSound(if (strong) successSound else tapSound)
        vibrate(if (strong) 30L else 16L)
    }

    private fun successFeedback() {
        playSound(successSound, 0.95f)
        vibrate(45L)
    }

    private fun vibrate(duration: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(duration)
        }
    }

    private fun startSplash() {
        val splash = findViewById<View>(R.id.splashPage)
        val bar = findViewById<ProgressBar>(R.id.splashProgress)
        val animator = ValueAnimator.ofInt(0, 100)
        animator.duration = 1500L
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { bar.progress = it.animatedValue as Int }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                splash.animate().alpha(0f).setDuration(260L)
                    .withEndAction { splash.visibility = View.GONE }
                    .start()
            }
        })
        animator.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
