package com.codleander.gamebooster

/**
 * The four selectable sound packs. Three of them ship their own four interface effects and a
 * fifty-second ambient loop; SILENT has no resources at all and is handled before any lookup.
 */
object SoundPacks {

    const val NEON = 0
    const val SOFT = 1
    const val ARCADE = 2
    const val SILENT = 3

    const val TAP = 0
    const val SUCCESS = 1
    const val BOOST = 2
    const val ERROR = 3

    private val neon = intArrayOf(
        R.raw.sfx_tap_neon,
        R.raw.sfx_success_neon,
        R.raw.sfx_boost_neon,
        R.raw.sfx_error_neon
    )

    private val soft = intArrayOf(
        R.raw.sfx_tap_soft,
        R.raw.sfx_success_soft,
        R.raw.sfx_boost_soft,
        R.raw.sfx_error_soft
    )

    private val arcade = intArrayOf(
        R.raw.sfx_tap_arcade,
        R.raw.sfx_success_arcade,
        R.raw.sfx_boost_arcade,
        R.raw.sfx_error_arcade
    )

    fun hasAudio(pack: Int): Boolean = pack != SILENT

    /** Resources to load into a SoundPool, in [TAP], [SUCCESS], [BOOST], [ERROR] order. */
    fun effects(pack: Int): IntArray = when (pack) {
        SOFT -> soft
        ARCADE -> arcade
        else -> neon
    }

    fun effect(pack: Int, effect: Int): Int =
        if (!hasAudio(pack)) 0 else effects(pack).getOrElse(effect) { 0 }

    fun ambient(pack: Int): Int = when (pack) {
        SOFT -> R.raw.ambient_soft
        ARCADE -> R.raw.ambient_arcade
        else -> R.raw.ambient_neon
    }
}
