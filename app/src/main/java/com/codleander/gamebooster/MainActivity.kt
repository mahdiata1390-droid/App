package com.codleander.gamebooster

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.media.ToneGenerator
import android.media.AudioManager
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var ramView: TextView
    private lateinit var thermalView: TextView
    private lateinit var refreshView: TextView
    private lateinit var gamesContainer: LinearLayout
    private lateinit var lightningIcon: ImageView
    private lateinit var statusView: TextView
    private lateinit var boostView: TextView
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
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)

        boostView.setOnClickListener {
            pulse(boostView); pulse(lightningIcon); feedback(true)
            statusView.text = "BOOST READY • SELECT A GAME BELOW"
        }
        findViewById<Button>(R.id.dnd).setOnClickListener {
            pulse(it); feedback(false); openDndSettings()
        }
        findViewById<Button>(R.id.refreshStats).setOnClickListener {
            pulse(it); feedback(false); updateStats()
            statusView.text = "SYSTEM REFRESHED • PERFORMANCE DATA UPDATED"
        }
        updateStats()
        loadGames()
    }

    override fun onDestroy() {
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun feedback(strong: Boolean) {
        tone?.startTone(if (strong) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 90)
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            val duration = if (strong) 35L else 20L
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }

    private fun pulse(view: View) {
        view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_electric))
    }

    private fun updateStats() {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val used = info.totalMem - info.availMem
        ramView.text = String.format(Locale.US, "RAM\\n%.1f / %.1f GB", used / 1073741824.0, info.totalMem / 1073741824.0)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val status = if (android.os.Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        thermalView.text = "THERMAL\\n" + thermalLabel(status)
        @Suppress("DEPRECATION")
        refreshView.text = String.format(Locale.US, "DISPLAY\\n%.0f Hz", windowManager.defaultDisplay.refreshRate)
    }

    private fun thermalLabel(status: Int) = when (status) {
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
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        gamesContainer.removeAllViews()
        apps.forEach { ri ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(18, 12, 10, 12)
                background = getDrawable(R.drawable.neo_card)
                elevation = 6f
            }
            val icon = ImageView(this).apply {
                setImageDrawable(ri.loadIcon(pm))
                layoutParams = LinearLayout.LayoutParams(44, 44)
            }
            val name = TextView(this).apply {
                text = ri.loadLabel(pm)
                textSize = 15f
                setTextColor(Color.rgb(234, 248, 255))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12 }
            }
            val launch = Button(this).apply {
                text = "BOOST"
                setTextColor(Color.rgb(89, 216, 255))
                background = getDrawable(R.drawable.neo_button)
                elevation = 4f
                setOnClickListener {
                    pulse(this); pulse(lightningIcon); feedback(true)
                    statusView.text = "BOOST READY • LAUNCHING ${ri.loadLabel(pm).toString().uppercase(Locale.US)}"
                    launchGame(ri.activityInfo.packageName)
                }
            }
            row.addView(icon); row.addView(name); row.addView(launch)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 12
            gamesContainer.addView(row, lp)
        }
    }

    private fun launchGame(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun openDndSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
}