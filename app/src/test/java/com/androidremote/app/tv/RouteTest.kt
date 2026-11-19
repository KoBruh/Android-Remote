package com.androidremote.app.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException

class RouteTest {

    @Test
    fun `a socket that can be bound uses the Wi-Fi network`() {
        val bound = mutableListOf<String>()
        val route = bindOrDefaultRoute("wlan", bind = { bound += it }, trace = {})
        assertEquals(listOf("wlan"), bound)
        assertEquals("bound to Wi-Fi network wlan", route)
    }

    @Test
    fun `a refused bind falls back to the default route`() {
        // An always-on VPN refuses binding around it: "Binding socket to network 108 failed: EPERM".
        val lines = mutableListOf<String>()
        val route = bindOrDefaultRoute(
            "108",
            bind = { throw SocketException("Binding socket to network 108 failed: EPERM (Operation not permitted)") },
            trace = { lines += it },
        )
        assertEquals("default route", route)
        assertTrue(lines.single(), lines.single().contains("EPERM"))
    }

    @Test
    fun `no network means the default route`() {
        assertEquals("default route", bindOrDefaultRoute<String>(null, bind = { error("not called") }, trace = {}))
    }
}
