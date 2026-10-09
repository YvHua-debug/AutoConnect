package com.example.composestarter.monitor

import org.junit.Assert.*
import org.junit.Test

class ForegroundTrackerTest {
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
