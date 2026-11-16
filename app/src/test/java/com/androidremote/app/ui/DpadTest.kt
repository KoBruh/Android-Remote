package com.androidremote.app.ui

import androidx.compose.ui.geometry.Offset
import com.androidremote.app.ui.remote.DpadZone
import com.androidremote.app.ui.remote.zoneAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DpadTest {

    private val d = 900f
    private val c = d / 2

    @Test
    fun `the middle is the centre button`() {
        assertEquals(DpadZone.Center, zoneAt(Offset(c, c), d))
        assertEquals(DpadZone.Center, zoneAt(Offset(c + 100f, c - 100f), d))
    }

    @Test
    fun `the ring is split into four slices`() {
        assertEquals(DpadZone.Up, zoneAt(Offset(c, 60f), d))
        assertEquals(DpadZone.Down, zoneAt(Offset(c, d - 60f), d))
        assertEquals(DpadZone.Left, zoneAt(Offset(60f, c), d))
        assertEquals(DpadZone.Right, zoneAt(Offset(d - 60f, c), d))
    }

    @Test
    fun `slices meet on the diagonals`() {
        // Just either side of the top-right diagonal, well outside the centre button.
        assertEquals(DpadZone.Up, zoneAt(Offset(c + 240f, c - 260f), d))
        assertEquals(DpadZone.Right, zoneAt(Offset(c + 260f, c - 240f), d))
    }

    @Test
    fun `touches outside the disc are ignored`() {
        assertNull(zoneAt(Offset(10f, 10f), d))
        assertNull(zoneAt(Offset(d - 10f, d - 10f), d))
    }
}
