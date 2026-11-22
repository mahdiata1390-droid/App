package com.codleander.gamebooster

object SessionGuards {
    /**
     * A running session writes its heartbeat every two seconds. The UI, the widget and the
     * tile are only ever evaluated while the device is awake, so a heartbeat that is minutes
     * old means the session process is gone and its leftover state must not block a new boost.
     */
    const val HEARTBEAT_STALE_MS = 3L * 60L * 1000L

    fun capElapsed(now: Long, last: Long, maxStep: Long): Long =
        if (last <= 0L) 0L else (now - last).coerceIn(0L, maxStep)

    fun isSessionStale(now: Long, heartbeat: Long, staleMs: Long = HEARTBEAT_STALE_MS): Boolean =
        heartbeat <= 0L || now - heartbeat > staleMs

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
