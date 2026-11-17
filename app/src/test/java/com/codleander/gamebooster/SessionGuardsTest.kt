package com.codleander.gamebooster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionGuardsTest {
    @Test
    fun capElapsed_never_counts_negative_or_excessive_time() {
        assertEquals(0L, SessionGuards.capElapsed(100L, 200L, 4000L))
        assertEquals(4000L, SessionGuards.capElapsed(10_000L, 0L, 4000L))
        assertEquals(4000L, SessionGuards.capElapsed(10_000L, 1_000L, 4000L))
        assertEquals(2500L, SessionGuards.capElapsed(3_500L, 1_000L, 4000L))
    }

    @Test
    fun restoreLong_requires_service_change_and_expected_current_value() {
        assertTrue(SessionGuards.shouldRestoreLong(true, 60_000L, 1_800_000L, 1_800_000L))
        assertFalse(SessionGuards.shouldRestoreLong(false, 60_000L, 1_800_000L, 1_800_000L))
        assertFalse(SessionGuards.shouldRestoreLong(true, 60_000L, 900_000L, 1_800_000L))
        assertFalse(SessionGuards.shouldRestoreLong(true, -1L, 1_800_000L, 1_800_000L))
    }

    @Test
    fun restoreInt_does_not_overwrite_user_change() {
        assertTrue(SessionGuards.shouldRestoreInt(true, 128, 128, 128))
        assertFalse(SessionGuards.shouldRestoreInt(true, 128, 200, 128))
        assertFalse(SessionGuards.shouldRestoreInt(false, 128, 128, 128))
        assertFalse(SessionGuards.shouldRestoreInt(true, -1, 128, 128))
    }

    @Test
    fun wrongForeground_stops_only_after_grace_and_ignores_service_package() {
        assertFalse(SessionGuards.shouldStopForWrongForeground("game", "game", "booster", 20_000L, 15_000L))
        assertFalse(SessionGuards.shouldStopForWrongForeground("booster", "game", "booster", 20_000L, 15_000L))
        assertFalse(SessionGuards.shouldStopForWrongForeground("home", "game", "booster", 10_000L, 15_000L))
        assertTrue(SessionGuards.shouldStopForWrongForeground("home", "game", "booster", 20_000L, 15_000L))
    }
}
