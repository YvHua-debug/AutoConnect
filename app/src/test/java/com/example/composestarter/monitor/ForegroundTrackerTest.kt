package com.example.composestarter.monitor

import org.junit.Assert.*
import org.junit.Test

class ForegroundTrackerTest {
    private fun ForegroundTracker.requiredPackage() = packageRequiringVpn { it.startsWith("listed.") }

    @Test fun unlistedFloatingWindowDoesNotEndListedSession() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.accept("listed.main", "Main", 200L, false)
        tracker.accept("unlisted.window", "Window", 201L, true)
        assertEquals("unlisted.window", tracker.packageName)
        assertEquals("listed.main", tracker.requiredPackage())
    }

    @Test fun multiResumeKeepsListedWindowWhenAnotherAppHasFocus() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.accept("unlisted.window", "Window", 200L, true)
        assertEquals("listed.main", tracker.requiredPackage())
    }

    @Test fun listedFloatingWindowKeepsVpnUntilItCloses() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("unlisted.main", "Main", 100L, true)
        tracker.accept("listed.window", "Window", 200L, true)
        tracker.accept("listed.window", "Window", 300L, false)
        tracker.accept("unlisted.main", "Main", 301L, true)
        assertEquals("listed.window", tracker.requiredPackage())
        tracker.stopped("listed.window", "Window", 400L)
        assertNull(tracker.requiredPackage())
        assertEquals("unlisted.main", tracker.packageName)
    }

    @Test fun normalFullscreenSwitchStopsListedSession() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.accept("listed.main", "Main", 200L, false)
        tracker.accept("unlisted.main", "Main", 201L, true)
        tracker.stopped("listed.main", "Main", 300L)
        assertNull(tracker.requiredPackage())
    }

    @Test fun closingOneListedWindowKeepsOtherListedWindow() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.first", "Main", 100L, true)
        tracker.accept("listed.second", "Main", 200L, true)
        assertEquals("listed.second", tracker.requiredPackage())
        tracker.stopped("listed.second", "Main", 300L)
        assertEquals("listed.first", tracker.requiredPackage())
        tracker.stopped("listed.first", "Main", 400L)
        assertNull(tracker.requiredPackage())
    }

    @Test fun stoppingOldActivityKeepsNewActivityInSamePackageVisible() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.accept("listed.main", "Voice", 200L, true)
        tracker.stopped("listed.main", "Main", 300L)
        assertEquals("listed.main", tracker.requiredPackage())
        tracker.stopped("listed.main", "Voice", 400L)
        assertNull(tracker.requiredPackage())
    }

    @Test fun overlappingHistoryDoesNotResurrectStoppedWindow() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.stopped("listed.main", "Main", 200L)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.accept("listed.main", "Main", 150L, false)
        assertNull(tracker.requiredPackage())
    }

    @Test fun overlappingOldStopDoesNotRemoveReopenedWindow() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.stopped("listed.main", "Main", 200L)
        tracker.accept("listed.main", "Main", 300L, true)
        tracker.stopped("listed.main", "Main", 200L)
        assertEquals("listed.main", tracker.requiredPackage())
    }

    @Test fun deviceRestartClearsWindowsAndIgnoresOldHistory() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.deviceRestarted(200L)
        tracker.accept("listed.main", "Main", 100L, true)
        assertNull(tracker.requiredPackage())
        assertNull(tracker.packageName)
        tracker.accept("listed.main", "Main", 300L, true)
        assertEquals("listed.main", tracker.requiredPackage())
    }

    @Test fun transparentVpnControlDoesNotRetainVpnDemand() {
        val tracker = ForegroundTracker(tracksVisibility = true)
        tracker.accept("listed.main", "Main", 100L, true)
        tracker.accept("listed.control", "com.follow.clash.QuickActionActivity", 200L, true)
        tracker.stopped("listed.main", "Main", 300L)
        assertNull(tracker.requiredPackage())
    }

    @Test fun legacyAndroidUsesForegroundOnly() {
        val tracker = ForegroundTracker(tracksVisibility = false)
        tracker.accept("listed.main", "Main", 100L, true)
        assertEquals("listed.main", tracker.requiredPackage())
        tracker.accept("unlisted.window", "Window", 200L, true)
        assertNull(tracker.requiredPackage())
    }

    @Test fun frozenForNineMinutesDoesNotSkipAppLaunch() {
        val lastPoll = 1_000_000L
        val resumedAt = lastPoll + 60_000L
        val now = lastPoll + 9 * 60_000L
        assertTrue(ForegroundTracker.queryStart(lastPoll, now) < resumedAt)
        val tracker = ForegroundTracker()
        tracker.accept("launcher", "Home", lastPoll, true)
        tracker.accept("com.openai.chatgpt", "Main", resumedAt, true)
        assertEquals("com.openai.chatgpt", tracker.packageName)
    }

    @Test fun frozenForNineMinutesDoesNotSkipAppExit() {
        val tracker = ForegroundTracker()
        tracker.accept("com.openai.chatgpt", "Main", 1_000_000L, true)
        val start = ForegroundTracker.queryStart(1_000_000L, 1_540_000L)
        assertTrue(start < 1_060_000L)
        tracker.accept("launcher", "Home", 1_060_000L, true)
        tracker.accept("com.openai.chatgpt", "Main", 1_061_000L, false)
        assertEquals("launcher", tracker.packageName)
    }

    @Test fun pausedAppWithoutReplacementIsNoLongerForeground() {
        val tracker = ForegroundTracker()
        tracker.accept("com.openai.chatgpt", "Main", 100L, true)
        tracker.accept("com.openai.chatgpt", "Main", 200L, false)
        assertNull(tracker.packageName)
    }

    @Test fun overlappingOldResumeDoesNotResurrectClosedApp() {
        val tracker = ForegroundTracker()
        tracker.accept("com.openai.chatgpt", "Main", 100L, true)
        tracker.accept("com.openai.chatgpt", "Main", 200L, false)
        tracker.accept("com.openai.chatgpt", "Main", 100L, true)
        assertNull(tracker.packageName)
    }

    @Test fun pausedOldActivityDoesNotClearNewActivityInSamePackage() {
        val tracker = ForegroundTracker()
        tracker.accept("com.openai.chatgpt", "Main", 100L, true)
        tracker.accept("com.openai.chatgpt", "Voice", 200L, true)
        tracker.accept("com.openai.chatgpt", "Main", 201L, false)
        assertEquals("com.openai.chatgpt", tracker.packageName)
    }

    @Test fun vpnControlPageDoesNotReplaceChatgpt() {
        val tracker = ForegroundTracker()
        tracker.accept("com.openai.chatgpt", "Main", 100L, true)
        tracker.accept("com.follow.clash", "com.follow.clash.QuickActionActivity", 200L, true)
        assertEquals("com.openai.chatgpt", tracker.packageName)
        tracker.accept("com.follow.clash", "com.follow.clash.MainActivity", 300L, true)
        assertEquals("com.follow.clash", tracker.packageName)
    }

    @Test fun firstQueryAndClockRollbackUseLongHistory() {
        assertEquals(10_000_000L - 86_400_000L, ForegroundTracker.queryStart(0, 10_000_000L))
        assertEquals(10_000_000L - 86_400_000L, ForegroundTracker.queryStart(20_000_000L, 10_000_000L))
    }
}
