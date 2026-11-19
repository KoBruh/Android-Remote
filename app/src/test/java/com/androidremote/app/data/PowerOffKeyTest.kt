package com.androidremote.app.data

import com.androidremote.app.tv.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerOffKeyTest {

    @Test
    fun `a TV that is on or unknown gets the chosen off key`() {
        assertEquals(KeyCodes.SLEEP, PowerOffKey.Sleep.keyFor(wasOn = true))
        assertEquals(KeyCodes.SLEEP, PowerOffKey.Sleep.keyFor(wasOn = null))
        assertEquals(KeyCodes.TV_POWER, PowerOffKey.TvPower.keyFor(wasOn = true))
        assertEquals(KeyCodes.POWER, PowerOffKey.Power.keyFor(wasOn = true))
    }

    @Test
    fun `a TV that reports off is woken with POWER whatever the setting`() {
        for (key in PowerOffKey.entries) assertEquals(key.name, KeyCodes.POWER, key.keyFor(wasOn = false))
    }

    @Test
    fun `the off keys are the Android key codes`() {
        assertEquals(listOf(26, 223, 177), PowerOffKey.entries.map { it.keyCode })
    }
}
