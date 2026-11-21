package com.codleander.gamebooster

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class GameInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val installed: Boolean
)

object GameLibrary {

    private val POPULAR = linkedMapOf(
        "com.activision.callofduty.shooter" to "Call of Duty: Mobile",
        "com.tencent.ig" to "PUBG Mobile",
        "com.mojang.minecraftpe" to "Minecraft",
        "com.epicgames.fortnite" to "Fortnite",
        "com.roblox.client" to "Roblox",
        "com.miHoYo.GenshinImpact" to "Genshin Impact",
        "com.dts.freefireth" to "Free Fire",
        "com.supercell.clashofclans" to "Clash of Clans",
        "com.riotgames.league.wildrift" to "Wild Rift",
        "com.mobile.legends" to "Mobile Legends"
    )

    fun load(context: Context): List<GameInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        val installed = LinkedHashMap<String, GameInfo>()
        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || installed.containsKey(pkg)) continue
            val app = info.activityInfo.applicationInfo
            val isGame = app.category == ApplicationInfo.CATEGORY_GAME ||
                (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                POPULAR.containsKey(pkg)
            if (!isGame) continue
            installed[pkg] = GameInfo(
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                installed = true
            )
        }

        val result = ArrayList<GameInfo>(POPULAR.size + installed.size)
        POPULAR.forEach { (pkg, label) ->
            result.add(installed[pkg] ?: GameInfo(pkg, label, null, false))
        }
        installed.values
            .filter { it.packageName !in POPULAR }
            .sortedBy { it.label.lowercase() }
            .forEach { result.add(it) }
        return result
    }

    fun isPopular(packageName: String): Boolean = POPULAR.containsKey(packageName)
}
