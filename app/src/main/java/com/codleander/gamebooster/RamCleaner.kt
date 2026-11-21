package com.codleander.gamebooster

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build

object RamCleaner {

    data class Result(val freedMb: Int, val killed: Int)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun free(context: Context, limit: Int): Result {
        if (!hasUsageAccess(context)) return Result(0, 0)
        val before = DeviceStats.availableMb(context)

        val usageStats = runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 6L * 60L * 60L * 1000L, now)
        }.getOrNull() ?: return Result(0, 0)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val launchers = runCatching {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())

        var killed = 0
        usageStats.sortedByDescending { it.lastTimeUsed }.forEach { stat ->
            if (killed >= limit) return@forEach
            val pkg = stat.packageName ?: return@forEach
            if (pkg == context.packageName || pkg == "android" || launchers.contains(pkg)) return@forEach
            val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                ?: return@forEach
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem) return@forEach
            runCatching { activityManager.killBackgroundProcesses(pkg) }
            killed++
        }

        runCatching { Runtime.getRuntime().gc() }
        val after = DeviceStats.availableMb(context)
        return Result((after - before).coerceAtLeast(0L).toInt(), killed)
    }
}
