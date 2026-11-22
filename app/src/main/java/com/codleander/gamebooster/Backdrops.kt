package com.codleander.gamebooster

import android.content.Context

/**
 * Selectable wallpapers for the screen backdrop. Index 0 keeps the bundled default artwork so
 * an untouched install looks exactly as before; the rest are the full-resolution pieces in
 * `res/drawable-nodpi`, rendered by `tools/make_art.py`.
 */
object Backdrops {

    const val DEFAULT = 0

    private val drawables = intArrayOf(
        0,
        R.drawable.wp_aurora,
        R.drawable.wp_ember,
        R.drawable.wp_emerald,
        R.drawable.wp_cyber,
        R.drawable.wp_storm,
        R.drawable.wp_ice,
        R.drawable.wp_nebula,
        R.drawable.wp_sunset
    )

    private val labels = intArrayOf(
        R.string.backdrop_default,
        R.string.backdrop_aurora,
        R.string.backdrop_ember,
        R.string.backdrop_emerald,
        R.string.backdrop_cyber,
        R.string.backdrop_storm,
        R.string.backdrop_ice,
        R.string.backdrop_nebula,
        R.string.backdrop_sunset
    )

    val count: Int get() = drawables.size

    fun drawable(index: Int): Int {
        val id = drawables.getOrElse(index) { 0 }
        return if (id == 0) R.drawable.bg_hero else id
    }

    fun labelRes(index: Int): Int = labels.getOrElse(index) { R.string.backdrop_default }

    fun label(context: Context, index: Int): String = context.getString(labelRes(index))
}
