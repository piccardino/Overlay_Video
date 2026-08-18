package com.example.vhpmatchpresentation

import com.example.vhpmatchpresentation.data.AppUpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testVersionComparison() {
        assertTrue(AppUpdateManager.isVersionNewer("v1.1", "1.0"))
        assertTrue(AppUpdateManager.isVersionNewer("1.1.0", "1.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("2.0", "1.9"))
        assertTrue(AppUpdateManager.isVersionNewer("v1.0.1", "1.0.0"))

        assertFalse(AppUpdateManager.isVersionNewer("1.0", "1.0"))
        assertFalse(AppUpdateManager.isVersionNewer("v1.0", "1.0"))
        assertFalse(AppUpdateManager.isVersionNewer("0.9", "1.0"))
        assertFalse(AppUpdateManager.isVersionNewer("", "1.0"))
    }
}
