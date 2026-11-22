package com.codleander.gamebooster

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat

/**
 * Resolves the accented colors from the current theme so a user-selected accent
 * (blue / violet / emerald / ember) reaches both XML attributes and code-drawn views.
 */
object Accent {

    const val BLUE = 0
    const val VIOLET = 1
    const val EMERALD = 2
    const val EMBER = 3

    fun themeRes(id: Int): Int = when (id) {
        VIOLET -> R.style.Theme_GameBooster_Violet
        EMERALD -> R.style.Theme_GameBooster_Emerald
        EMBER -> R.style.Theme_GameBooster_Ember
        else -> R.style.Theme_GameBooster
    }

    fun resolve(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) {
            return ContextCompat.getColor(context, fallback)
        }
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    fun bright(context: Context): Int =
        resolve(context, R.attr.gbAccentBright, R.color.gb_accent_bright)

    fun soft(context: Context): Int =
        resolve(context, R.attr.gbAccentSoft, R.color.gb_accent_soft)

    fun base(context: Context): Int =
        resolve(context, R.attr.gbAccent, R.color.gb_accent)

    fun aurora(context: Context): Int =
        resolve(context, R.attr.gbAurora1, R.color.gb_accent_bright)

    fun auroraDeep(context: Context): Int =
        resolve(context, R.attr.gbAurora2, R.color.gb_accent)
}
