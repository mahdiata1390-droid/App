package com.codleander.gamebooster

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon

/**
 * Publishes the installed games as launcher shortcuts so a long-press on the FLASH BOOSTER
 * icon offers "Boost <game>" — the one-tap entry point the big booster apps ship.
 */
object GameShortcuts {

    const val ACTION_BOOST_GAME = "com.codleander.gamebooster.BOOST_GAME"
    const val EXTRA_PACKAGE = "boost_package"

    private const val MAX_SHORTCUTS = 4
    private const val SHORT_LABEL_MAX = 12

    fun sync(context: Context, games: List<GameInfo>) {
        val manager = context.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return
        val installed = games.filter { it.installed }.take(MAX_SHORTCUTS)
        if (installed.isEmpty()) {
            runCatching { manager.removeAllDynamicShortcuts() }
            return
        }
        val shortcuts = installed.mapIndexedNotNull { rank, game ->
            runCatching { build(context, game, rank) }.getOrNull()
        }
        if (shortcuts.isEmpty()) return
        runCatching { manager.setDynamicShortcuts(shortcuts) }
    }

    private fun build(context: Context, game: GameInfo, rank: Int): ShortcutInfo {
        val label = game.label.trim().ifBlank { game.packageName }
        val short = if (label.length > SHORT_LABEL_MAX) label.take(SHORT_LABEL_MAX - 1) + "…" else label
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_BOOST_GAME
            putExtra(EXTRA_PACKAGE, game.packageName)
        }
        val builder = ShortcutInfo.Builder(context, "boost_${game.packageName}")
            .setShortLabel(short)
            .setLongLabel(context.getString(R.string.shortcut_long_label, label))
            .setRank(rank)
            .setIntent(intent)
        icon(game)?.let { builder.setIcon(it) }
        return builder.build()
    }

    private fun icon(game: GameInfo): Icon? {
        val drawable = game.icon ?: return null
        return runCatching {
            val size = 108
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            Icon.createWithBitmap(bitmap)
        }.getOrNull()
    }
}
