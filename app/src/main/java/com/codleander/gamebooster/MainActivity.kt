package com.codleander.gamebooster

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var ramView: TextView
    private lateinit var thermalView: TextView
    private lateinit var refreshView: TextView
    private lateinit var gamesContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.codleander.gamebooster.R.layout.activity_main)
        ramView = findViewById(com.codleander.gamebooster.R.id.ram)
        thermalView = findViewById(com.codleander.gamebooster.R.id.thermal)
        refreshView = findViewById(com.codleander.gamebooster.R.id.refresh)
        gamesContainer = findViewById(com.codleander.gamebooster.R.id.games)
        findViewById<Button>(com.codleander.gamebooster.R.id.dnd).setOnClickListener { openDndSettings() }
        updateStats()
        loadGames()
    }

    private fun updateStats() {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val used = info.totalMem - info.availMem
        ramView.text = String.format(Locale.US, "RAM\n%.1f / %.1f GB", used / 1073741824.0, info.totalMem / 1073741824.0)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val status = if (android.os.Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        thermalView.text = "THERMAL\n" + thermalLabel(status)
        refreshView.text = String.format(Locale.US, "DISPLAY\n%.0f Hz", windowManager.defaultDisplay.refreshRate)
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
                setPadding(16, 8, 8, 8)
                setBackgroundColor(Color.rgb(18, 22, 34))
            }
            val name = TextView(this).apply {
                text = ri.loadLabel(pm)
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val launch = Button(this).apply {
                text = "BOOST"
                setOnClickListener { launchGame(ri.activityInfo.packageName) }
            }
            row.addView(name)
            row.addView(launch)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 10
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
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
}
