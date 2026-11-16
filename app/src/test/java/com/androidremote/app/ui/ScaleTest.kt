package com.androidremote.app.ui

import androidx.compose.ui.geometry.Offset
import com.androidremote.app.ui.remote.DpadZone
import com.androidremote.app.ui.remote.zoneAt
import com.androidremote.app.ui.theme.Tokens
import com.androidremote.app.ui.theme.refPxToDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScaleTest {

    @Test
    fun `the full reference width maps to the full screen width`() {
        assertEquals(412f, refPxToDp(Tokens.RefWidthPx, 412f), 0.001f)
        assertEquals(360f, refPxToDp(Tokens.RefWidthPx, 360f), 0.001f)
    }

    @Test
    fun `reference pixels match the dp guide values on a 412 dp screen`() {
        assertEquals(348.1f, refPxToDp(Tokens.DpadDiameter, 412f), 0.1f) // D-pad, about 348 dp
        assertEquals(79.8f, refPxToDp(Tokens.BackButton.width, 412f), 0.1f) // back, about 80 dp
        assertEquals(0f, refPxToDp(0f, 412f), 0f)
    }

    @Test
    fun `scaling is linear in the screen width`() {
        assertEquals(2 * refPxToDp(100f, 400f), refPxToDp(100f, 800f), 0.001f)
        assertEquals(1f, refPxToDp(1f, Tokens.RefWidthPx), 0.001f)
    }

    @Test
    fun `d-pad touches map to the centre and the four slices`() {
        val d = 900f
        val c = d / 2
        assertEquals(DpadZone.Center, zoneAt(Offset(c, c), d))
        assertEquals(DpadZone.Up, zoneAt(Offset(c, 60f), d))
        assertEquals(DpadZone.Down, zoneAt(Offset(c, d - 60f), d))
        assertEquals(DpadZone.Left, zoneAt(Offset(60f, c), d))
        assertEquals(DpadZone.Right, zoneAt(Offset(d - 60f, c), d))
        assertNull("corner is outside the disc", zoneAt(Offset(10f, 10f), d))
    }
}
