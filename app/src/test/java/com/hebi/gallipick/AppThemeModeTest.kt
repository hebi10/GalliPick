package com.hebi.gallipick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeModeTest {
    @Test fun storedChoiceRestoresAndUnknownValuesFollowTheDevice() {
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStored("DARK"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromStored("LIGHT"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored("invalid"))
    }

    @Test fun explicitChoiceOverridesTheDeviceWhileSystemChoiceTracksIt() {
        assertTrue(AppThemeMode.DARK.isDark(false))
        assertFalse(AppThemeMode.LIGHT.isDark(true))
        assertTrue(AppThemeMode.SYSTEM.isDark(true))
        assertFalse(AppThemeMode.SYSTEM.isDark(false))
    }
}
