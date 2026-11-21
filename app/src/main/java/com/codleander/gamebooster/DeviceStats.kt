package com.codleander.gamebooster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object DeviceStats {

    data class Memory(val usedBytes: Long, val availableBytes: Long, val totalBytes: Long) {
        val usedPercent: Int
            get() = if (totalBytes <= 0L) 0 else ((usedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    data class Battery(val level: Int, val charging: Boolean, val temperatureC: Float?)

    fun memory(context: Context): Memory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val used = (info.totalMem - info.availMem).coerceAtLeast(0L)
        return Memory(used, info.availMem, info.totalMem)
    }

    fun availableMb(context: Context): Long = memory(context).availableBytes / (1024L * 1024L)

    fun battery(context: Context): Battery {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperature = if (tempTenths > 0) tempTenths / 10f else null
        return Battery(percent, charging, temperature)
    }

    fun thermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return PowerManager.THERMAL_STATUS_NONE
        return (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    }

    fun thermalLabelRes(status: Int): Int = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> R.string.thermal_normal
        PowerManager.THERMAL_STATUS_LIGHT -> R.string.thermal_light
        PowerManager.THERMAL_STATUS_MODERATE -> R.string.thermal_moderate
        PowerManager.THERMAL_STATUS_SEVERE -> R.string.thermal_severe
        PowerManager.THERMAL_STATUS_CRITICAL -> R.string.thermal_critical
        PowerManager.THERMAL_STATUS_EMERGENCY -> R.string.thermal_emergency
        PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.thermal_shutdown
        else -> R.string.not_available
    }

    fun networkLabelRes(context: Context): Int {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        return when {
            caps == null -> R.string.net_offline
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> R.string.net_offline
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.net_wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.net_mobile
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.string.net_ethernet
            else -> R.string.net_online
        }
    }

    /** Returns the best round-trip in milliseconds, or null when every probe failed. */
    fun pingMs(): Long? {
        val hosts = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        var best: Long? = null
        for (host in hosts) {
            val ms = runCatching {
                val address = InetAddress.getByName(host)
                val start = System.nanoTime()
                if (!address.isReachable(1000)) return@runCatching null
                ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull()
            if (ms != null && (best == null || ms < best!!)) best = ms
        }
        if (best == null) {
            best = runCatching {
                val start = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1200) }
                ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            }.getOrNull()
        }
        return best
    }
}
