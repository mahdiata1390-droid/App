package com.codleander.gamebooster

object SessionGuards {
    fun capElapsed(now: Long, last: Long, maxStep: Long): Long =
        if (last <= 0L) 0L else (now - last).coerceIn(0L, maxStep)

    fun shouldRestoreLong(
        changedByService: Boolean,
        previous: Long,
        current: Long?,
        serviceValue: Long
    ): Boolean =
        changedByService && previous > 0L && current == serviceValue

    fun shouldRestoreInt(
        changedByService: Boolean,
        previous: Int,
        current: Int?,
        serviceValue: Int
    ): Boolean =
        changedByService && previous >= 0 && current == serviceValue

    fun shouldStopForWrongForeground(
        foreground: String?,
        target: String,
        servicePackage: String,
        elapsedSinceStart: Long,
        graceMs: Long
    ): Boolean =
        foreground != null &&
            foreground != target &&
            foreground != servicePackage &&
            elapsedSinceStart >= graceMs
}
