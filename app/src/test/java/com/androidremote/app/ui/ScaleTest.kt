package com.androidremote.app.ui

import com.androidremote.app.ui.theme.Tokens
import com.androidremote.app.ui.theme.refPxToDp
import org.junit.Assert.assertEquals
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
}
