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
import android.net.VpnService
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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
        private const val REQ_VPN = 1002
        private const val REQ_BACKUP_EXPORT = 1003
        private const val REQ_BACKUP_IMPORT = 1004

        const val ACTION_QUICK_BOOST = "com.codleander.gamebooster.QUICK_BOOST"

        private const val PRIVATE_DNS_SETTINGS_ACTION = "android.settings.PRIVATE_DNS_SETTINGS"

        private const val SOUND_NEON = 0
        private const val SOUND_SOFT = 1
        private const val SOUND_ARCADE = 2
        private const val SOUND_SILENT = 3
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
    private var aurora: AuroraBackgroundView? = null
    private var pendingQuickBoost = false
    private var pendingGameBoost: String? = null
    private var lastSessionActive: Boolean? = null
    private var integrityBlocked = false
    private var backdropPaused = false

    private var sounds: SoundPool? = null
    private val soundIds = Array(SoundPacks.SILENT) { IntArray(4) }

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
        setTheme(Accent.themeRes(Prefs.accent(this)))
        if (!IntegrityCheck.isTrusted(this)) {
            // No content view is inflated for an untrusted copy, so every later UI refresh
            // must be skipped as well instead of dereferencing missing views.
            integrityBlocked = true
            showTamperBlock()
            return
        }
        setContentView(R.layout.activity_main)

        initSounds()
        applyAppearance()
        animateBackdrop()
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

        if (intent?.action == ACTION_QUICK_BOOST) {
            pendingQuickBoost = true
            ui.postDelayed({ if (pendingQuickBoost) requestQuickBoost() }, 900L)
        }
        handleGameShortcut(intent)
    }

    /**
     * Launcher shortcuts carry a package name so a long-press can boost one specific game.
     * The library may still be loading, so the request waits for it instead of being dropped.
     */
    private fun handleGameShortcut(intent: Intent?) {
        if (intent?.action != GameShortcuts.ACTION_BOOST_GAME) return
        val packageName = intent.getStringExtra(GameShortcuts.EXTRA_PACKAGE) ?: return
        pendingGameBoost = packageName
        applyPendingGameBoost()
    }

    private fun applyPendingGameBoost() {
        val packageName = pendingGameBoost ?: return
        if (gamesLoading || games.isEmpty()) return
        pendingGameBoost = null
        val game = games.firstOrNull { it.packageName == packageName && it.installed }
        if (game == null) {
            toast(getString(R.string.shortcut_missing))
            return
        }
        selectedGame = game
        updateHomeChip()
        refreshHomeStatus()
        if (activeBoost() == null) {
            startBoost(game)
        } else {
            showPage(PAGE_HOME)
        }
    }

    private fun showTamperBlock() {
        AlertDialog.Builder(this)
            .setTitle(R.string.integrity_title)
            .setMessage(R.string.integrity_message)
            .setCancelable(false)
            .setPositiveButton(R.string.integrity_close) { _, _ -> finishAffinity() }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (integrityBlocked) return
        if (intent.action == ACTION_QUICK_BOOST) {
            pendingQuickBoost = true
            requestQuickBoost()
        }
        handleGameShortcut(intent)
    }

    private fun requestQuickBoost() {
        pendingQuickBoost = false
        if (activeBoost() != null) {
            refreshSessionUi()
            refreshHomeStatus()
            return
        }
        if (selectedGame == null && gamesLoading) {
            // The tile and the widget boost the last played game. Deciding before the
            // library has loaded would silently turn a game boost into a device boost.
            pendingQuickBoost = true
            return
        }
        showPage(PAGE_HOME)
        startBoost(selectedGame)
    }

    override fun onResume() {
        super.onResume()
        if (integrityBlocked) return
        if (backdropPaused) {
            backdropPaused = false
            animateBackdrop()
        }
        aurora?.resume()
        LiquidGlass.resume(this)
        startStatsLoop()
        updateHomeChip()
        refreshSessionUi()
        refreshHomeStatus()
        refreshPermissions()
        syncToggles()
        PrivateDns.ensureStarted(this)
        refreshQuickActions()
        if (currentPage == PAGE_HISTORY) refreshHistory()
    }

    override fun onPause() {
        backdropPaused = true
        findViewById<View>(R.id.backdrop)?.animate()?.cancel()
        aurora?.pause()
        LiquidGlass.pause(this)
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
        val navHome = findViewById<View>(R.id.navHome)
        val navGames = findViewById<View>(R.id.navGames)
        val navHistory = findViewById<View>(R.id.navHistory)
        val navSettings = findViewById<View>(R.id.navSettings)
        navHome.setOnClickListener { tapFeedback(); showPage(PAGE_HOME) }
        navGames.setOnClickListener { tapFeedback(); showPage(PAGE_LIBRARY) }
        navHistory.setOnClickListener { tapFeedback(); showPage(PAGE_HISTORY) }
        navSettings.setOnClickListener { tapFeedback(); showPage(PAGE_SETTINGS) }
        findViewById<View>(R.id.menuButton).setOnClickListener { tapFeedback(); showPage(PAGE_SETTINGS) }
        findViewById<View>(R.id.crownButton).setOnClickListener { tapFeedback(); showPage(PAGE_ABOUT) }
        pressFeedback(navHome, navGames, navHistory, navSettings)
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
            val pageView = findViewById<View>(id)
            if (key == page) {
                if (pageView.visibility == View.VISIBLE) return@forEach
                pageView.animate().cancel()
                pageView.visibility = View.VISIBLE
                pageView.alpha = 0f
                pageView.translationY = dp(12).toFloat()
                pageView.animate().alpha(1f).translationY(0f).setDuration(230L)
                    .setInterpolator(DecelerateInterpolator()).start()
                animatePageContent(pageView)
            } else if (pageView.visibility != View.GONE) {
                pageView.animate().cancel()
                pageView.visibility = View.GONE
            }
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
            val color = if (selected) {
                Accent.bright(this)
            } else {
                ContextCompat.getColor(this, R.color.gb_muted)
            }
            val icon = findViewById<ImageView>(navIconIds[index])
            icon.setColorFilter(color)
            findViewById<TextView>(navLabelIds[index]).setTextColor(color)
            icon.animate().cancel()
            if (selected) {
                icon.scaleX = 1f
                icon.scaleY = 1f
                icon.animate().scaleX(1.16f).scaleY(1.16f).setDuration(120L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        icon.animate().scaleX(1f).scaleY(1f).setDuration(170L).start()
                    }
                    .start()
            } else {
                icon.scaleX = 1f
                icon.scaleY = 1f
            }
        }
    }

    // --------------------------------------------------------------------- home

    private fun bindHome() {
        val boostButton = findViewById<View>(R.id.homeBoostButton)
        val chip = findViewById<View>(R.id.homeGameChip)
        val stop = findViewById<View>(R.id.stopBoostButton)
        boostButton.setOnClickListener {
            feedback(true)
            startBoost(selectedGame)
        }
        findViewById<View>(R.id.homeLibrary).setOnClickListener {
            tapFeedback()
            showPage(PAGE_LIBRARY)
        }
        chip.setOnClickListener { tapFeedback(); showPage(PAGE_LIBRARY) }
        stop.setOnClickListener { stopActiveSession() }
        pressFeedback(boostButton, chip, stop)

        val qaDnd = findViewById<View>(R.id.qaDnd)
        val qaFps = findViewById<View>(R.id.qaFps)
        val qaDns = findViewById<View>(R.id.qaDns)
        val qaRam = findViewById<View>(R.id.qaRam)
        qaDnd.setOnClickListener { tapFeedback(); toggleDndQuick() }
        qaFps.setOnClickListener { tapFeedback(); toggleFpsQuick() }
        qaDns.setOnClickListener { tapFeedback(); choosePrivateDns() }
        qaRam.setOnClickListener { tapFeedback(); freeRamQuick() }
        pressFeedback(qaDnd, qaFps, qaDns, qaRam)
    }

    private fun refreshQuickActions() {
        val dndState = findViewById<TextView>(R.id.qaDndState)
        when {
            !DndHelper.hasAccess(this) -> {
                dndState.setText(R.string.dnd_need_access)
                dndState.setTextColor(ContextCompat.getColor(this, R.color.gb_muted))
            }
            DndHelper.isOn(this) -> {
                dndState.setText(R.string.dnd_on)
                dndState.setTextColor(ContextCompat.getColor(this, R.color.gb_green))
            }
            else -> {
                dndState.setText(R.string.dnd_off)
                dndState.setTextColor(ContextCompat.getColor(this, R.color.gb_muted))
            }
        }
        val fpsState = findViewById<TextView>(R.id.qaFpsState)
        val fpsOn = Prefs.fpsHud(this)
        fpsState.setText(if (fpsOn) R.string.qa_on else R.string.qa_off)
        fpsState.setTextColor(
            ContextCompat.getColor(this, if (fpsOn) R.color.gb_green else R.color.gb_muted)
        )
        refreshDnsUi()
    }

    private fun refreshDnsUi() {
        val active = PrivateDns.active(this) != null
        val label = PrivateDns.label(this)
        findViewById<TextView>(R.id.dnsValue).text = label
        val state = findViewById<TextView>(R.id.qaDnsState)
        state.text = label
        state.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.gb_green else R.color.gb_muted)
        )
    }

    private fun toggleDndQuick() {
        val result = DndHelper.toggle(this)
        if (result == null) {
            DndHelper.openAccessSettings(this)
            toast(getString(R.string.dnd_access_needed))
            refreshQuickActions()
            return
        }
        playSound(if (result) SoundPacks.SUCCESS else SoundPacks.TAP, 0.7f)
        toast(getString(if (result) R.string.dnd_on_toast else R.string.dnd_off_toast))
        refreshQuickActions()
    }

    private fun toggleFpsQuick() {
        val enabled = !Prefs.fpsHud(this)
        Prefs.setFpsHud(this, enabled)
        playSound(SoundPacks.TAP, 0.6f)
        refreshQuickActions()
        toast(getString(if (enabled) R.string.fps_hud_enabled else R.string.fps_hud_disabled))
        if (activeBoost() != null) {
            val intent = Intent(this, BoosterService::class.java).apply {
                action = BoosterService.ACTION_REFRESH_HUD
            }
            runCatching { startService(intent) }
        }
    }

    private fun freeRamQuick() {
        feedback(true)
        toast(getString(R.string.ram_cleaning))
        Thread {
            val result = RamCleaner.free(this, 8)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                toast(
                    if (result.freedMb > 0) {
                        getString(R.string.ram_freed, result.freedMb)
                    } else {
                        getString(R.string.ram_cleared, result.killed)
                    }
                )
                refreshDeviceStats()
            }
        }.start()
    }

    private fun animateBackdrop() {
        if (backdropPaused) return
        val backdrop = findViewById<View>(R.id.backdrop) ?: return
        backdrop.animate().cancel()
        backdrop.translationY = 0f
        backdrop.animate().translationY(-dp(26).toFloat()).setDuration(9000L)
            .setInterpolator(LinearInterpolator())
            .withEndAction { swingBackdrop(backdrop) }
            .start()
    }

    private fun swingBackdrop(backdrop: View) {
        if (isFinishing || isDestroyed || backdropPaused) return
        backdrop.animate().translationY(0f).setDuration(9000L)
            .setInterpolator(LinearInterpolator())
            .withEndAction { animateBackdrop() }
            .start()
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (!statsRunning) return
            refreshDeviceStats()
            refreshPing()
            updateHomeChip()
            refreshSessionUi()
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
        animateBar(findViewById(R.id.statRamBar), memory.usedPercent)
        findViewById<TextView>(R.id.statRamSub).text = getString(
            R.string.ram_usage,
            memory.usedBytes / 1073741824.0,
            memory.totalBytes / 1073741824.0
        )

        val battery = DeviceStats.battery(this)
        if (battery.level >= 0) {
            findViewById<TextView>(R.id.statBatteryValue).text =
                getString(R.string.percent_value, battery.level)
            findViewById<TextView>(R.id.statBatterySub).text = getString(
                if (battery.charging) R.string.charging else R.string.on_battery
            )
            animateBar(findViewById(R.id.statBatteryBar), battery.level)
        } else {
            findViewById<TextView>(R.id.statBatteryValue).text = getString(R.string.gain_none)
            findViewById<TextView>(R.id.statBatterySub).text = getString(R.string.not_available)
            animateBar(findViewById(R.id.statBatteryBar), 0)
        }

        val temperature = battery.temperatureC
        val thermal = DeviceStats.thermalStatus(this)
        if (temperature != null) {
            findViewById<TextView>(R.id.statTempValue).text =
                getString(R.string.temp_celsius, temperature)
            animateBar(
                findViewById(R.id.statTempBar),
                (((temperature - 20f) / 45f) * 100f).toInt().coerceIn(0, 100)
            )
        } else {
            findViewById<TextView>(R.id.statTempValue).text = getString(R.string.gain_none)
            animateBar(findViewById(R.id.statTempBar), 0)
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
                    animateBar(findViewById(R.id.statNetworkBar), latency.toInt().coerceIn(0, 200))
                } else {
                    findViewById<TextView>(R.id.statNetworkValue).text =
                        getString(R.string.timeout)
                    animateBar(findViewById(R.id.statNetworkBar), 0)
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
        val session = activeBoost()

        when {
            session != null -> {
                chip.visibility = View.VISIBLE
                name.text = session.label
                state.text = getString(
                    R.string.session_running,
                    formatDuration(System.currentTimeMillis() - session.startedAt)
                )
                showAppIcon(
                    icon,
                    if (session.isGame) runtime.getString("target_package", null) else null
                )
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
        // updateHomeChip() runs from the one-second stats loop; reloading the launcher icon
        // through PackageManager on every tick is a needless main-thread IPC.
        val key = packageName ?: ""
        if (view.tag == key) return
        view.tag = key
        val drawable = if (packageName == null) null else runCatching {
            packageManager.getApplicationIcon(packageName)
        }.getOrNull()
        if (drawable != null) {
            view.setImageDrawable(drawable)
            view.clearColorFilter()
        } else {
            view.setImageResource(R.drawable.ic_gamepad)
            view.setColorFilter(Accent.bright(this))
        }
    }

    private fun refreshHomeStatus() {
        val status = findViewById<TextView>(R.id.homeStatus)
        val session = activeBoost()
        val game = selectedGame
        when {
            session != null -> status.text =
                getString(R.string.status_active_short, session.label)
            game != null -> status.text = getString(R.string.status_selected, game.label)
            else -> status.setText(R.string.status_ready)
        }
    }

    // ------------------------------------------------------------------ library

    private fun bindLibrary() {
        val filterAll = findViewById<View>(R.id.filterAll)
        val filterPopular = findViewById<View>(R.id.filterPopular)
        val filterInstalled = findViewById<View>(R.id.filterInstalled)
        filterAll.setOnClickListener { tapFeedback(); setFilter(FILTER_ALL) }
        filterPopular.setOnClickListener { tapFeedback(); setFilter(FILTER_POPULAR) }
        filterInstalled.setOnClickListener { tapFeedback(); setFilter(FILTER_INSTALLED) }
        pressFeedback(filterAll, filterPopular, filterInstalled)

        findViewById<View>(R.id.gamesSearchToggle).setOnClickListener {
            tapFeedback()
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
            val recent = runCatching { GameLibrary.lastPlayed(this, loaded) }.getOrNull()
            ui.post {
                gamesLoading = false
                games.clear()
                games.addAll(loaded)
                renderGames()
                if (recent != null && selectedGame == null && activeBoost() == null) {
                    selectedGame = recent
                    updateHomeChip()
                    refreshHomeStatus()
                }
                GameShortcuts.sync(this, loaded)
                if (pendingQuickBoost) requestQuickBoost()
                applyPendingGameBoost()
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
                setColorFilter(Accent.bright(this@MainActivity))
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
                if (game.installed) Accent.bright(this@MainActivity)
                else ContextCompat.getColor(this@MainActivity, R.color.gb_muted)
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val plus = ImageView(this).apply {
            setImageResource(R.drawable.ic_plus)
            setColorFilter(Accent.bright(this@MainActivity))
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
        pressFeedback(card)
        return card
    }

    private fun onGameClicked(game: GameInfo) {
        if (!game.installed) {
            openStore(game.packageName)
            return
        }
        selectedGame = game
        playSound(SoundPacks.TAP, 0.7f)
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
        val back = findViewById<View>(R.id.boostBack)
        val cancel = findViewById<View>(R.id.boostCancel)
        val settings = findViewById<View>(R.id.boostSettings)
        back.setOnClickListener { tapFeedback(); abortBoost() }
        cancel.setOnClickListener { tapFeedback(); abortBoost() }
        settings.setOnClickListener {
            tapFeedback()
            abortBoost()
            showPage(PAGE_SETTINGS)
        }
        pressFeedback(back, cancel, settings)
    }

    private fun startBoost(game: GameInfo?) {
        if (boosting) return
        if (activeBoost() != null) {
            toast(getString(R.string.session_already_active))
            return
        }
        boosting = true
        boostCancelled = false
        selectedGame = game ?: selectedGame
        showPage(PAGE_BOOSTING)
        feedback(true)
        playSound(SoundPacks.BOOST, 0.9f)
        vibratePattern(0L, 26L, 60L, 40L)

        val ring = findViewById<BoostRingView>(R.id.boostRing)
        ring.setActive(true)
        ring.setProgressNow(0f)
        ring.animateTo(1f, 3400L)
        val percent = findViewById<TextView>(R.id.boostPercent)
        percent.tag = 0
        percent.text = getString(R.string.boost_percent, 0)
        setStageText(R.string.optimizing_device, instant = true)
        for (index in 0 until 4) setStepState(index, STEP_PENDING)

        val target = game ?: selectedGame
        val mode = target?.let { GameProfiles.get(this, it.packageName).mode }
            ?: Prefs.boostMode(this)
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
                            setStageText(stepTitleRes(step))
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
                            animateBoostPercent((step + 1) * 25)
                            playSound(SoundPacks.TAP, 0.4f, 1.45f)
                            vibrate(12L)
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
                dot.animate().cancel()
                dot.scaleX = 1f
                dot.scaleY = 1f
                icon.animate().cancel()
                icon.scaleX = 0.55f
                icon.scaleY = 0.55f
                icon.animate().scaleX(1f).scaleY(1f).setDuration(240L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            STEP_ACTIVE -> {
                dot.setBackgroundResource(R.drawable.bg_step_circle)
                icon.setColorFilter(Accent.bright(this))
                text.setTextColor(Accent.soft(this))
                dot.animate().cancel()
                dot.scaleX = 1.06f
                dot.scaleY = 1.06f
                dot.animate().scaleX(1.24f).scaleY(1.24f).setDuration(150L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { dot.animate().scaleX(1f).scaleY(1f).setDuration(180L).start() }
                    .start()
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
        DndHelper.restoreDeviceBoost(this)
    }

    private fun finishBoost(game: GameInfo?, freedMb: Int, thermal: Int, startedAt: Long) {
        boosting = false
        for (index in 0 until 4) setStepState(index, STEP_DONE)
        val boostRing = findViewById<BoostRingView>(R.id.boostRing)
        boostRing.setProgressNow(1f)
        boostRing.setActive(false)
        animateBoostPercent(100)
        setStageText(R.string.boost_done_title, instant = true)

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

        when {
            game == null -> activateDeviceBoost(startedAt)
            !launchGameSession(game) -> restoreDeviceBoostDnd()
        }

        ui.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            showPage(PAGE_HOME)
            refreshSessionUi()
            findViewById<TextView>(R.id.homeStatus).text =
                getString(R.string.status_boost_freed, freedMb)
            refreshHistory()
        }, 900L)
    }

    private fun abortBoost() {
        if (boosting) {
            boosting = false
            boostCancelled = true
            findViewById<BoostRingView>(R.id.boostRing).setActive(false)
            restoreDeviceBoostDnd()
        }
        showPage(PAGE_HOME)
    }

    private data class ActiveBoost(val label: String, val startedAt: Long, val isGame: Boolean)

    private fun activeBoost(): ActiveBoost? {
        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        val gameLabel = runtime.getString("target_label", null)
        val gameStart = runtime.getLong("session_started_at", 0L)
        if (!gameLabel.isNullOrBlank() && gameStart > 0L) {
            val heartbeat = runtime.getLong("last_heartbeat", 0L)
            if (!SessionGuards.isSessionStale(System.currentTimeMillis(), heartbeat)) {
                return ActiveBoost(gameLabel, gameStart, true)
            }
            // The session process is gone (killed or crashed) but its runtime keys and the
            // widget/tile state stayed behind. Drop the session keys so a new boost is not
            // blocked by a session that no longer exists; recovery markers for settings the
            // service may have changed are intentionally left untouched.
            runtime.edit()
                .remove("target_label")
                .remove("session_started_at")
                .remove("active_play_ms")
                .remove("last_heartbeat")
                .apply()
        }
        if (runtime.getBoolean("device_boost_active", false)) {
            val deviceStart = runtime.getLong("device_boost_started_at", 0L)
            if (deviceStart > 0L) {
                return ActiveBoost(getString(R.string.device_boost_label), deviceStart, false)
            }
        }
        return null
    }

    private fun activateDeviceBoost(startedAt: Long) {
        getSharedPreferences("booster_runtime", MODE_PRIVATE).edit()
            .putBoolean("device_boost_active", true)
            .putLong("device_boost_started_at", startedAt)
            .apply()
    }

    private fun refreshSessionUi() {
        if (boosting) return
        val session = activeBoost()
        val title = findViewById<TextView>(R.id.homeBoostTitle)
        val hint = findViewById<TextView>(R.id.homeBoostHint)
        val stop = findViewById<View>(R.id.stopBoostButton)
        val ring = findViewById<BoostRingView>(R.id.homeRing)
        if (session != null) {
            title.setText(R.string.boosting_active)
            hint.text = getString(
                R.string.session_running,
                formatDuration(System.currentTimeMillis() - session.startedAt)
            )
            stop.visibility = View.VISIBLE
            ring.setProgressNow(1f)
            ring.setActive(true)
        } else {
            title.setText(R.string.boost)
            hint.setText(R.string.tap_to_boost)
            stop.visibility = View.GONE
            ring.setProgressNow(0f)
            ring.setActive(false)
        }
        val active = session != null
        if (lastSessionActive != active) {
            lastSessionActive = active
            BoosterWidgetProvider.refreshAll(this)
            BoosterTileService.refresh(this)
        }
    }

    private fun stopActiveSession() {
        val session = activeBoost() ?: return
        feedback(true)
        val runtime = getSharedPreferences("booster_runtime", MODE_PRIVATE)
        if (session.isGame) {
            val stopIntent = Intent(this, BoosterService::class.java).apply {
                action = BoosterService.ACTION_STOP
            }
            if (runCatching { startService(stopIntent) }.isFailure) {
                runCatching { stopService(Intent(this, BoosterService::class.java)) }
            }
            runtime.edit()
                .remove("target_label")
                .remove("session_started_at")
                .remove("active_play_ms")
                .remove("last_heartbeat")
                .remove("peak_thermal")
                .apply()
        } else {
            restoreDeviceBoostDnd()
            runtime.edit()
                .remove("device_boost_active")
                .remove("device_boost_started_at")
                .apply()
        }
        refreshSessionUi()
        updateHomeChip()
        refreshHomeStatus()
        refreshHistory()
    }

    private fun launchGameSession(game: GameInfo): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(game.packageName)
        if (launch == null) {
            toast(getString(R.string.launch_failed, game.label))
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val profile = GameProfiles.get(this, game.packageName)
        val serviceIntent = Intent(this, BoosterService::class.java).apply {
            action = BoosterService.ACTION_START
            putExtra(BoosterService.EXTRA_PACKAGE, game.packageName)
            putExtra("profile_dnd", profile.dnd)
            putExtra("profile_awake", true)
            putExtra("profile_brightness", profile.brightnessLock)
            putExtra("profile_refresh", profile.refreshLock || Prefs.refreshLock(this@MainActivity))
            putExtra("profile_hud", profile.hud)
        }
        // Request the foreground service while this activity still owns the foreground.
        // Android 12+ rejects a foreground-service start from the background, which is what
        // happens when the game is opened before the service is requested.
        val serviceStarted = runCatching {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        }.isSuccess
        if (!serviceStarted) {
            toast(getString(R.string.session_start_failed, game.label))
            return false
        }
        if (runCatching { startActivity(launch) }.isFailure) {
            toast(getString(R.string.launch_failed, game.label))
            runCatching { stopService(Intent(this, BoosterService::class.java)) }
            return false
        }
        selectedGame = game
        return true
    }

    // ------------------------------------------------------------------ settings

    private fun bindSettings() {
        val profile = findViewById<View>(R.id.profileCard)
        val boostMode = findViewById<View>(R.id.rowBoostMode)
        val appearance = findViewById<View>(R.id.rowAppearance)
        val language = findViewById<View>(R.id.rowLanguage)
        val permissions = findViewById<View>(R.id.rowPermissions)
        profile.setOnClickListener { tapFeedback(); editProfileName() }
        syncProfileName()
        boostMode.setOnClickListener { tapFeedback(); chooseBoostMode() }
        appearance.setOnClickListener { tapFeedback(); chooseAppearance() }
        language.setOnClickListener { tapFeedback(); chooseLanguage() }
        permissions.setOnClickListener { tapFeedback(); showPage(PAGE_PERMISSIONS) }
        val profilesRow = findViewById<View>(R.id.rowProfiles)
        val dnsRow = findViewById<View>(R.id.rowDns)
        val accentRow = findViewById<View>(R.id.rowAccent)
        val soundRow = findViewById<View>(R.id.rowSound)
        val backdropRow = findViewById<View>(R.id.rowBackdrop)
        profilesRow.setOnClickListener { tapFeedback(); openGameProfiles() }
        dnsRow.setOnClickListener { tapFeedback(); choosePrivateDns() }
        accentRow.setOnClickListener { tapFeedback(); chooseAccent() }
        soundRow.setOnClickListener { tapFeedback(); chooseSoundPack() }
        backdropRow.setOnClickListener { tapFeedback(); chooseBackdrop() }
        findViewById<Switch>(R.id.refreshLockSwitch).setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            toggleRefreshLock(checked)
        }
        findViewById<Switch>(R.id.ambientSwitch).setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setAmbient(this, checked)
            playSound(SoundPacks.TAP, 0.6f)
        }
        findViewById<Switch>(R.id.thermalGuardSwitch).setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setThermalGuard(this, checked)
            playSound(SoundPacks.TAP, 0.6f)
        }
        findViewById<Switch>(R.id.breakSwitch).setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setBreakMinutes(this, if (checked) 60 else 0)
            playSound(SoundPacks.TAP, 0.6f)
        }
        val backupRow = findViewById<View>(R.id.rowBackup)
        backupRow.setOnClickListener { tapFeedback(); chooseBackup() }
        pressFeedback(backupRow)
        pressFeedback(
            profile, boostMode, appearance, language, permissions,
            profilesRow, dnsRow, accentRow, soundRow, backdropRow
        )

        val autoBoost = findViewById<Switch>(R.id.autoBoostSwitch)
        autoBoost.setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setAutoBoost(this, checked)
            playSound(SoundPacks.TAP, 0.6f)
        }
        val gameMode = findViewById<Switch>(R.id.gameModeSwitch)
        gameMode.setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setGameMode(this, checked)
            if (checked && !hasDndAccess()) {
                toast(getString(R.string.game_mode_needs_access))
                DndHelper.openAccessSettings(this)
            }
        }
        val notifications = findViewById<Switch>(R.id.notificationSwitch)
        notifications.setOnCheckedChangeListener { _, checked ->
            if (syncingToggles) return@setOnCheckedChangeListener
            Prefs.setNotifications(this, checked)
            if (checked && !hasNotificationPermission()) requestNotificationPermission()
        }

        findViewById<TextView>(R.id.boostModeValue).setText(boostModeLabelRes(Prefs.boostMode(this)))
        findViewById<TextView>(R.id.appearanceValue).setText(appearanceLabelRes())
        findViewById<TextView>(R.id.languageValue).text =
            if (Prefs.language(this) == "fa") "فارسی" else "English"
        syncToggles()
        refreshSettingValues()
        refreshDnsUi()
    }

    private fun syncProfileName() {
        val saved = Prefs.displayName(this)
        findViewById<TextView>(R.id.profileName).text =
            saved.ifBlank { getString(R.string.profile_name_hint) }
    }

    private fun editProfileName() {
        val input = EditText(this).apply {
            setText(Prefs.displayName(this@MainActivity))
            hint = getString(R.string.profile_name_hint)
            setSingleLine(true)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_edit_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                Prefs.setDisplayName(this, input.text.toString())
                syncProfileName()
                tapFeedback()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun syncToggles() {
        syncingToggles = true
        findViewById<Switch>(R.id.autoBoostSwitch).isChecked = Prefs.autoBoost(this)
        findViewById<Switch>(R.id.gameModeSwitch).isChecked = Prefs.gameMode(this)
        findViewById<Switch>(R.id.notificationSwitch).isChecked =
            Prefs.notifications(this) && hasNotificationPermission()
        findViewById<Switch>(R.id.ambientSwitch).isChecked = Prefs.ambient(this)
        findViewById<Switch>(R.id.thermalGuardSwitch).isChecked = Prefs.thermalGuard(this)
        findViewById<Switch>(R.id.breakSwitch).isChecked = Prefs.breakMinutes(this) > 0
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

    private fun appearanceLabelRes(): Int = when {
        Prefs.glass(this) -> R.string.appearance_glass
        Prefs.amoled(this) -> R.string.appearance_amoled
        else -> R.string.appearance_dark
    }

    private fun chooseAppearance() {
        val options = arrayOf(
            getString(R.string.appearance_dark),
            getString(R.string.appearance_amoled),
            getString(R.string.appearance_glass)
        )
        val selected = when {
            Prefs.glass(this) -> 2
            Prefs.amoled(this) -> 1
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance)
            .setSingleChoiceItems(options, selected) { dialog, which ->
                Prefs.setGlass(this, which == 2)
                Prefs.setAmoled(this, which == 1)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseBackup() {
        val options = arrayOf(
            getString(R.string.backup_export),
            getString(R.string.backup_import)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.backup)
            .setItems(options) { _, which ->
                if (which == 0) startBackupExport() else startBackupImport()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startBackupExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = Backup.MIME
            putExtra(Intent.EXTRA_TITLE, Backup.FILE_NAME)
        }
        runCatching { startActivityForResult(intent, REQ_BACKUP_EXPORT) }
            .onFailure { toast(getString(R.string.settings_unavailable)) }
    }

    private fun startBackupImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_BACKUP_IMPORT) }
            .onFailure { toast(getString(R.string.settings_unavailable)) }
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
        val glass = Prefs.glass(this)
        findViewById<View>(R.id.root).setBackgroundResource(
            when {
                glass -> R.drawable.bg_liquid_glass
                amoled -> R.drawable.bg_amoled
                else -> R.drawable.bg_app
            }
        )
        val backdropIndex = Prefs.backdrop(this)
        val customWallpaper = backdropIndex != Backdrops.DEFAULT
        val backdrop = findViewById<ImageView>(R.id.backdrop)
        backdrop.setImageResource(Backdrops.drawable(backdropIndex))
        backdrop.alpha = when {
            glass -> if (customWallpaper) 0.5f else 0.4f
            customWallpaper -> if (amoled) 0.62f else 0.9f
            amoled -> 0.22f
            else -> 0.55f
        }
        aurora = findViewById(R.id.aurora)
        aurora?.setPalette(Accent.aurora(this), Accent.auroraDeep(this))
        // A chosen wallpaper is the subject, so the moving aurora steps back instead of
        // washing it out. Liquid Glass wants the opposite: a bright field to refract.
        aurora?.alpha = when {
            glass -> 0.92f
            customWallpaper -> 0.35f
            amoled -> 0.45f
            else -> 0.72f
        }
        LiquidGlass.apply(this)
    }

    // --------------------------------------------------------------- permissions

    private fun bindPermissions() {
        val back = findViewById<View>(R.id.permissionsBack)
        val done = findViewById<View>(R.id.permissionsDone)
        val usage = findViewById<View>(R.id.permissionUsageRow)
        val notifications = findViewById<View>(R.id.permissionNotificationRow)
        val battery = findViewById<View>(R.id.permissionBatteryRow)
        val overlay = findViewById<View>(R.id.permissionOverlayRow)
        val systemSettings = findViewById<View>(R.id.permissionSettingsRow)
        val dnd = findViewById<View>(R.id.permissionDndRow)
        back.setOnClickListener { tapFeedback(); showPage(PAGE_SETTINGS) }
        done.setOnClickListener { tapFeedback(); showPage(PAGE_HOME) }
        usage.setOnClickListener { tapFeedback(); openUsageSettings() }
        notifications.setOnClickListener { tapFeedback(); requestNotificationPermission() }
        battery.setOnClickListener { tapFeedback(); requestBatteryExemption() }
        overlay.setOnClickListener { tapFeedback(); openOverlaySettings() }
        systemSettings.setOnClickListener { tapFeedback(); openWriteSettings() }
        dnd.setOnClickListener {
            tapFeedback()
            DndHelper.openAccessSettings(this)
            toast(getString(R.string.dnd_access_hint))
        }
        pressFeedback(back, done, usage, notifications, battery, overlay, systemSettings, dnd)
    }

    private fun refreshPermissions() {
        val usage = hasUsageAccess()
        val notifications = hasNotificationPermission()
        val battery = isIgnoringBatteryOptimizations()
        val overlay = Settings.canDrawOverlays(this)
        val writeSettings = Settings.System.canWrite(this)
        val dnd = DndHelper.hasAccess(this)

        bindPermissionStatus(R.id.permissionUsageStatus, usage)
        bindPermissionStatus(R.id.permissionNotificationStatus, notifications)
        bindPermissionStatus(R.id.permissionBatteryStatus, battery)
        bindPermissionStatus(R.id.permissionOverlayStatus, overlay)
        bindPermissionStatus(R.id.permissionSettingsStatus, writeSettings)
        bindPermissionStatus(R.id.permissionDndStatus, dnd)

        val granted = listOf(usage, notifications, battery, overlay, writeSettings, dnd).count { it }
        val total = 6
        val allGranted = granted == total
        findViewById<TextView>(R.id.permissionHeadline).text = if (allGranted) {
            getString(R.string.perm_all_granted)
        } else {
            getString(R.string.perm_some_title, granted, total)
        }
        findViewById<TextView>(R.id.permissionSubline).setText(
            if (allGranted) R.string.perm_all_granted_sub else R.string.perm_some_sub
        )
        findViewById<TextView>(R.id.permissionsValue).text = if (allGranted) {
            getString(R.string.perm_all_granted)
        } else {
            getString(R.string.perm_some_title, granted, total)
        }
    }

    private fun bindPermissionStatus(id: Int, granted: Boolean) {
        val view = findViewById<TextView>(id)
        view.setText(if (granted) R.string.granted else R.string.grant)
        view.setTextColor(
            if (granted) ContextCompat.getColor(this, R.color.gb_green) else Accent.bright(this)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BACKUP_EXPORT || requestCode == REQ_BACKUP_IMPORT) {
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) return
            val ok = if (requestCode == REQ_BACKUP_EXPORT) {
                Backup.export(this, uri)
            } else {
                Backup.import(this, uri)
            }
            if (requestCode == REQ_BACKUP_IMPORT && ok) {
                toast(getString(R.string.backup_restored))
                recreate()
            } else {
                toast(getString(if (ok) R.string.backup_done else R.string.backup_failed))
            }
            return
        }
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) {
            startPrivateDns()
            return
        }
        Prefs.setDnsEnabled(this, false)
        refreshDnsUi()
        toast(getString(R.string.dns_need_permission))
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
        val day = findViewById<View>(R.id.historyRangeDay)
        val week = findViewById<View>(R.id.historyRangeWeek)
        val month = findViewById<View>(R.id.historyRangeMonth)
        day.setOnClickListener { tapFeedback(); setHistoryRange(1) }
        week.setOnClickListener { tapFeedback(); setHistoryRange(7) }
        month.setOnClickListener { tapFeedback(); setHistoryRange(30) }
        pressFeedback(day, week, month)
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
        val labels = buildLabels(since, now, bucketCount, historyRangeDays)
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
        refreshWeeklyReport()
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

    /**
     * Labels come from the real middle of each bucket, so the hours, weekdays and dates under
     * the chart describe the data drawn above them instead of an assumed grid.
     */
    private fun buildLabels(since: Long, until: Long, buckets: Int, days: Int): List<String> {
        val bucketMs = ((until - since) / buckets).coerceAtLeast(1L)
        val zone = ZoneId.systemDefault()
        return (0 until buckets).map { index ->
            val middle = Instant.ofEpochMilli(since + bucketMs * index + bucketMs / 2).atZone(zone)
            when (days) {
                1 -> String.format(Locale.US, "%02d", middle.hour)
                7 -> middle.dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.SHORT, Locale.getDefault()
                )
                else -> middle.dayOfMonth.toString()
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
                setColorFilter(Accent.bright(this@MainActivity))
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
                if (record.isBoost) ContextCompat.getColor(this@MainActivity, R.color.gb_green)
                else Accent.bright(this@MainActivity)
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

    // ----------------------------------------------------------- personalization

    private fun refreshSettingValues() {
        findViewById<TextView>(R.id.profilesValue).text =
            when (val count = GameProfiles.customCount(this)) {
                0 -> getString(R.string.profiles_value_none)
                else -> getString(R.string.profiles_value_some, count)
            }
        findViewById<TextView>(R.id.accentValue).setText(accentLabelRes(Prefs.accent(this)))
        findViewById<TextView>(R.id.soundValue).setText(soundLabelRes(Prefs.soundPack(this)))
        findViewById<TextView>(R.id.backdropValue).text =
            Backdrops.label(this, Prefs.backdrop(this))
        syncingToggles = true
        findViewById<Switch>(R.id.refreshLockSwitch).isChecked = Prefs.refreshLock(this)
        findViewById<TextView>(R.id.appearanceValue).setText(appearanceLabelRes())
        syncingToggles = false
    }

    private fun accentLabelRes(accent: Int): Int = when (accent) {
        Accent.VIOLET -> R.string.accent_violet
        Accent.EMERALD -> R.string.accent_emerald
        Accent.EMBER -> R.string.accent_ember
        else -> R.string.accent_blue
    }

    private fun soundLabelRes(pack: Int): Int = when (pack) {
        SOUND_SOFT -> R.string.sound_soft
        SOUND_ARCADE -> R.string.sound_arcade
        SOUND_SILENT -> R.string.sound_silent
        else -> R.string.sound_neon
    }

    private fun chooseBackdrop() {
        val labels = Array(Backdrops.count) { index -> Backdrops.label(this, index) }
        AlertDialog.Builder(this)
            .setTitle(R.string.backdrop)
            .setSingleChoiceItems(labels, Prefs.backdrop(this)) { dialog, which ->
                Prefs.setBackdrop(this, which)
                dialog.dismiss()
                applyAppearance()
                refreshSettingValues()
                tapFeedback()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseAccent() {
        val labels = arrayOf(
            getString(R.string.accent_blue),
            getString(R.string.accent_violet),
            getString(R.string.accent_emerald),
            getString(R.string.accent_ember)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.accent_color)
            .setSingleChoiceItems(labels, Prefs.accent(this)) { dialog, which ->
                Prefs.setAccent(this, which)
                dialog.dismiss()
                tapFeedback()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseSoundPack() {
        val labels = arrayOf(
            getString(R.string.sound_neon),
            getString(R.string.sound_soft),
            getString(R.string.sound_arcade),
            getString(R.string.sound_silent)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.sound_pack)
            .setSingleChoiceItems(labels, Prefs.soundPack(this)) { dialog, which ->
                Prefs.setSoundPack(this, which)
                findViewById<TextView>(R.id.soundValue).setText(soundLabelRes(which))
                dialog.dismiss()
                playSound(SoundPacks.SUCCESS, 0.8f)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleRefreshLock(enabled: Boolean) {
        if (!enabled) {
            Prefs.setRefreshLock(this, false)
            toast(getString(R.string.refresh_off_toast))
            return
        }
        val hz = RefreshRateHelper.maxHz(this)
        if (!RefreshRateHelper.canControl(this)) {
            Prefs.setRefreshLock(this, false)
            findViewById<Switch>(R.id.refreshLockSwitch).isChecked = false
            toast(getString(R.string.refresh_need_settings))
            openWriteSettings()
            return
        }
        if (hz == null) {
            Prefs.setRefreshLock(this, false)
            findViewById<Switch>(R.id.refreshLockSwitch).isChecked = false
            toast(getString(R.string.refresh_unsupported))
            return
        }
        Prefs.setRefreshLock(this, true)
        tapFeedback()
        toast(getString(R.string.refresh_on_toast, hz.toInt()))
    }

    // -------------------------------------------------------------- private DNS

    /**
     * Private DNS is opt-in and needs the one-time VPN grant. Everything here reads the real
     * state back, so the row and the home chip never claim a tunnel that is not up.
     */
    private fun choosePrivateDns() {
        val resolvers = PrivateDns.builtInResolvers()
        val labels = ArrayList<String>(resolvers.size + 2)
        labels.add(getString(R.string.dns_off))
        resolvers.forEach { resolver ->
            labels.add(getString(R.string.dns_pick, getString(PrivateDns.titleRes(resolver.preset)), resolver.ip))
        }
        val custom = PrivateDns.resolver(this)
        val customLabel = if (custom != null && custom.preset == PrivateDns.PRESET_CUSTOM) {
            getString(R.string.dns_pick, getString(R.string.dns_custom), custom.ip)
        } else {
            getString(R.string.dns_custom)
        }
        labels.add(customLabel)

        val selected = when (val preset = Prefs.dnsPreset(this)) {
            PrivateDns.PRESET_OFF -> 0
            PrivateDns.PRESET_CUSTOM -> labels.size - 1
            else -> (resolvers.indexOfFirst { it.preset == preset } + 1).coerceAtLeast(0)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.private_dns)
            .setMessage(R.string.dns_about)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                when {
                    which == 0 -> disablePrivateDns()
                    which <= resolvers.size -> {
                        val resolver = resolvers[which - 1]
                        enablePrivateDns(resolver.preset, resolver.ip, resolver.host)
                    }
                    else -> editCustomDns()
                }
            }
            .setNegativeButton(R.string.cancel, null)
        if (Build.VERSION.SDK_INT >= 28) {
            builder.setNeutralButton(R.string.dns_system_settings) { _, _ -> openSystemPrivateDns() }
        }
        builder.show()
    }

    private fun enablePrivateDns(preset: Int, ip: String, host: String) {
        if (preset == PrivateDns.PRESET_CUSTOM &&
            (!PrivateDns.isValidIp(ip) || !PrivateDns.isValidHost(host))
        ) {
            toast(getString(R.string.dns_invalid))
            return
        }
        Prefs.setDnsPreset(this, preset)
        if (preset == PrivateDns.PRESET_CUSTOM) {
            Prefs.setDnsIp(this, ip)
            Prefs.setDnsHost(this, host)
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            if (runCatching { startActivityForResult(consent, REQ_VPN) }.isFailure) {
                Prefs.setDnsEnabled(this, false)
                toast(getString(R.string.dns_need_permission))
            }
            return
        }
        startPrivateDns()
    }

    private fun startPrivateDns() {
        PrivateDns.enable(
            this,
            Prefs.dnsPreset(this),
            Prefs.dnsIp(this),
            Prefs.dnsHost(this)
        )
        refreshDnsUi()
        playSound(SoundPacks.SUCCESS, 0.7f)
        toast(getString(R.string.dns_enabled_toast, PrivateDns.label(this)))
    }

    private fun disablePrivateDns() {
        PrivateDns.disable(this)
        refreshDnsUi()
        tapFeedback()
        toast(getString(R.string.dns_disabled_toast))
    }

    private fun editCustomDns() {
        val input = EditText(this).apply {
            hint = getString(R.string.dns_ip_hint)
            setText(Prefs.dnsIp(this@MainActivity))
            setSingleLine(true)
        }
        val host = EditText(this).apply {
            hint = getString(R.string.dns_host_hint)
            setText(Prefs.dnsHost(this@MainActivity))
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(input)
            addView(host)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dns_custom_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                enablePrivateDns(
                    PrivateDns.PRESET_CUSTOM,
                    input.text.toString(),
                    host.text.toString()
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The Private DNS screen has no public constant, so the action is opened by name. */
    private fun openSystemPrivateDns() {
        if (runCatching { startActivity(Intent(PRIVATE_DNS_SETTINGS_ACTION)) }.isSuccess) return
        if (runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.isFailure) {
            toast(getString(R.string.settings_unavailable))
        }
    }

    private fun openGameProfiles() {
        val installed = games.filter { it.installed }
        if (installed.isEmpty()) {
            toast(getString(R.string.profiles_empty))
            return
        }
        val labels = installed.map { game ->
            if (GameProfiles.has(this, game.packageName)) "●  ${game.label}" else game.label
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles)
            .setItems(labels) { _, index -> editGameProfile(installed[index]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editGameProfile(game: GameInfo) {
        val profile = GameProfiles.get(this, game.packageName)
        val options = arrayOf(
            getString(R.string.profile_dnd),
            getString(R.string.profile_brightness),
            getString(R.string.profile_refresh),
            getString(R.string.profile_hud)
        )
        val checked = booleanArrayOf(
            profile.dnd,
            profile.brightnessLock,
            profile.refreshLock,
            profile.hud
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profile_title, game.label))
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.save) { _, _ ->
                GameProfiles.save(
                    this,
                    game.packageName,
                    GameProfile(
                        dnd = checked[0],
                        brightnessLock = checked[1],
                        refreshLock = checked[2],
                        hud = checked[3],
                        mode = Prefs.boostMode(this),
                        custom = true
                    )
                )
                refreshSettingValues()
                tapFeedback()
                toast(getString(R.string.profile_saved, game.label))
            }
            .setNeutralButton(R.string.profile_reset) { _, _ ->
                GameProfiles.clear(this, game.packageName)
                refreshSettingValues()
                toast(getString(R.string.profile_reset_done, game.label))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshWeeklyReport() {
        val report = HistoryStore.weeklyReport(this, System.currentTimeMillis())
        animateValue(findViewById(R.id.weeklyBoostsValue), report.boosts.toString())
        findViewById<TextView>(R.id.weeklyPlayValue).text =
            if (report.playMs > 0) formatPlayTime(report.playMs) else getString(R.string.dash)
        findViewById<TextView>(R.id.weeklyFreedValue).text =
            if (report.freedMb > 0) {
                getString(R.string.mb_freed, report.freedMb)
            } else {
                getString(R.string.gain_none)
            }

        val trend = findViewById<TextView>(R.id.weeklyTrend)
        when {
            report.boosts == 0 -> {
                trend.setText(R.string.gain_none)
                trend.setTextColor(ContextCompat.getColor(this, R.color.gb_muted))
            }
            report.previousBoosts > 0 && report.boosts > report.previousBoosts -> {
                trend.text = getString(
                    R.string.weekly_trend_up,
                    ((report.boosts - report.previousBoosts) * 100) / report.previousBoosts
                )
                trend.setTextColor(ContextCompat.getColor(this, R.color.gb_green))
            }
            report.previousBoosts > 0 && report.boosts < report.previousBoosts -> {
                trend.text = getString(
                    R.string.weekly_trend_down,
                    ((report.previousBoosts - report.boosts) * 100) / report.previousBoosts
                )
                trend.setTextColor(ContextCompat.getColor(this, R.color.gb_red))
            }
            else -> {
                trend.setText(R.string.weekly_trend_flat)
                trend.setTextColor(Accent.bright(this))
            }
        }

        val goal = (report.boosts * 20).coerceIn(0, 100)
        animateBar(findViewById(R.id.weeklyGoalBar), goal)
        val summary = buildString {
            append(getString(R.string.weekly_active_days, report.activeDays))
            val top = report.topGame
            if (!top.isNullOrBlank()) {
                append(" • ")
                append(getString(R.string.weekly_top_game, top))
            }
            append(" • ")
            append(getString(R.string.weekly_goal, goal))
        }
        findViewById<TextView>(R.id.weeklyFoot).text = summary

        val card = findViewById<View>(R.id.weeklyCard)
        card.animate().cancel()
        card.alpha = 0f
        card.translationY = dp(16).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(320L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun animateValue(view: TextView, value: String) {
        if (view.text.toString() == value) return
        view.text = value
        view.animate().cancel()
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.animate().scaleX(1f).scaleY(1f).setDuration(230L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Staggered reveal for the children of the page that is sliding in. */
    private fun animatePageContent(pageView: View) {
        val container: ViewGroup = when (pageView) {
            is android.widget.ScrollView -> (pageView.getChildAt(0) as? ViewGroup) ?: return
            is ViewGroup -> pageView
            else -> return
        }
        val limit = minOf(container.childCount, 8)
        for (index in 0 until limit) {
            val child = container.getChildAt(index)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = dp(16).toFloat()
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay((index * 26).toLong())
                .setDuration(300L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // -------------------------------------------------------------------- about

    private fun bindAbout() {
        findViewById<View>(R.id.aboutBack).setOnClickListener { showPage(PAGE_HOME) }
    }

    // ------------------------------------------------------------------ feedback

    private fun initSounds() {
        sounds = runCatching {
            SoundPool.Builder().setMaxStreams(4).build().also { pool ->
                for (pack in SoundPacks.NEON until SoundPacks.SILENT) {
                    val effects = SoundPacks.effects(pack)
                    for (index in effects.indices) {
                        soundIds[pack][index] = pool.load(this, effects[index], 1)
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Plays one of the four interface effects from the pack the user picked. Both the file and
     * the character of the sound (volume, pitch) belong to that pack, so the selection is
     * audible rather than only stored.
     */
    private fun playSound(effect: Int, volume: Float = 0.85f, rate: Float = 1f) {
        val pack = Prefs.soundPack(this)
        if (!SoundPacks.hasAudio(pack)) return
        val (volumeScale, rateScale) = when (pack) {
            SoundPacks.SOFT -> 0.6f to 0.9f
            SoundPacks.ARCADE -> 1f to 1.32f
            else -> 1f to 1f
        }
        val id = soundIds.getOrNull(pack)?.getOrNull(effect) ?: 0
        if (id == 0) return
        val left = (volume * volumeScale).coerceIn(0f, 1f)
        sounds?.play(id, left, left, 1, 0, (rate * rateScale).coerceIn(0.5f, 2f))
    }

    private fun tapFeedback() {
        playSound(SoundPacks.TAP, 0.5f)
        vibrate(9L)
    }

    private fun pressFeedback(vararg views: View) {
        views.forEach { view ->
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                }
                false
            }
        }
    }

    private fun animateBar(barView: View, value: Int) {
        val bar = barView as? ProgressBar ?: return
        val target = value.coerceIn(0, bar.max)
        if (bar.progress == target) return
        (bar.tag as? ValueAnimator)?.cancel()
        val animation = ValueAnimator.ofInt(bar.progress, target).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            addUpdateListener { bar.progress = it.animatedValue as Int }
        }
        bar.tag = animation
        animation.start()
    }

    private fun animateBoostPercent(to: Int) {
        val view = findViewById<TextView>(R.id.boostPercent)
        val from = (view.tag as? Int) ?: 0
        if (from == to) return
        view.tag = to
        ValueAnimator.ofInt(from, to).apply {
            duration = 540L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                view.text = getString(R.string.boost_percent, animator.animatedValue as Int)
            }
            start()
        }
    }

    private fun setStageText(resId: Int, instant: Boolean = false) {
        val stage = findViewById<TextView>(R.id.boostStage)
        if (instant) {
            stage.animate().cancel()
            stage.alpha = 1f
            stage.translationY = 0f
            stage.setText(resId)
            return
        }
        if (stage.text.toString() == getString(resId)) return
        stage.animate().cancel()
        stage.animate().alpha(0f).translationY(-dp(4).toFloat()).setDuration(110L)
            .withEndAction {
                stage.setText(resId)
                stage.translationY = dp(4).toFloat()
                stage.animate().alpha(1f).translationY(0f).setDuration(180L).start()
            }
            .start()
    }

    private fun feedback(strong: Boolean) {
        playSound(if (strong) SoundPacks.SUCCESS else SoundPacks.TAP)
        vibrate(if (strong) 30L else 16L)
    }

    private fun successFeedback() {
        playSound(SoundPacks.SUCCESS, 0.95f)
        vibratePattern(0L, 30L, 50L, 22L, 40L, 55L)
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

    private fun vibratePattern(vararg timings: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(timings, -1)
            )
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(timings, -1)
        }
    }

    private fun startSplash() {
        val splash = findViewById<View>(R.id.splashPage)
        val bar = findViewById<ProgressBar>(R.id.splashProgress)

        val logo = findViewById<View>(R.id.splashLogo)
        logo.alpha = 0f
        logo.scaleX = 0.86f
        logo.scaleY = 0.86f
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(620L)
            .setInterpolator(DecelerateInterpolator()).start()
        val game = findViewById<View>(R.id.splashGame)
        val brand = findViewById<View>(R.id.splashBooster)
        val tagline = findViewById<View>(R.id.splashTagline)
        listOf(game to 140L, brand to 240L, tagline to 340L).forEach { (view, delay) ->
            view.alpha = 0f
            view.translationY = dp(14).toFloat()
            view.animate().alpha(1f).translationY(0f).setStartDelay(delay)
                .setDuration(520L).setInterpolator(DecelerateInterpolator()).start()
        }
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
