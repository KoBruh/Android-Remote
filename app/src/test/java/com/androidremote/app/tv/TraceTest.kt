package com.androidremote.app.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class TraceTest {

    @Test
    fun `describe names the exception and every cause`() {
        val error = SSLHandshakeException("Handshake failed").apply { initCause(IOException("Connection reset")) }
        assertEquals(
            "javax.net.ssl.SSLHandshakeException: Handshake failed\n  caused by java.io.IOException: Connection reset",
            error.describe(),
        )
    }

    @Test
    fun `describe stops at a cause loop`() {
        val a = IllegalStateException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)
        assertEquals(6, a.describe().lines().size)
    }
}
