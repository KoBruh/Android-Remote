package com.androidremote.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTrackerTest {

    private val tracker = SleepTracker()

    @Test
    fun `a new tracker lets the app connect`() {
        assertFalse(tracker.asleep)
    }

    @Test
    fun `power on a TV that is on or unknown puts it to sleep`() {
        tracker.onPowerKey(wasOn = true)
        assertTrue(tracker.asleep)
        tracker.onWanted()
        tracker.onPowerKey(wasOn = null)
        assertTrue(tracker.asleep)
    }

    @Test
    fun `power on a TV that reported off wakes it`() {
        tracker.onPowerKey(wasOn = true)
        tracker.onPowerKey(wasOn = false)
        assertFalse(tracker.asleep)
    }

    @Test
    fun `the TV's own power reports win`() {
        tracker.onPowerState(false)
        assertTrue("turned off with the TV's own remote", tracker.asleep)
        tracker.onPowerState(true)
        assertFalse("turned on with the TV's own remote", tracker.asleep)
    }

    @Test
    fun `an unknown power state changes nothing`() {
        tracker.onPowerKey(wasOn = true)
        tracker.onPowerState(null)
        assertTrue(tracker.asleep)
    }

    @Test
    fun `wanting the TV again clears sleep`() {
        tracker.onPowerState(false)
        tracker.onWanted()
        assertFalse(tracker.asleep)
    }
}
