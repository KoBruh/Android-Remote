package com.androidremote.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PairedDeviceTest {

    private fun pair(
        certName: String = "Living Room TV",
        certMac: String = "AA:BB:CC:DD:EE:FF",
        discoveredName: String? = null,
        discoveredPort: Int? = null,
        previous: PairedDevice? = null,
    ) = PairedDevice.fromPairing(
        fingerprint = "FP",
        certName = certName,
        certMac = certMac,
        host = "192.168.1.20",
        defaultPort = 6466,
        discoveredName = discoveredName,
        discoveredPort = discoveredPort,
        previous = previous,
    )

    @Test
    fun `a new TV takes its name and MAC from the certificate`() {
        assertEquals(
            PairedDevice("FP", "Living Room TV", "192.168.1.20", 6466, "AA:BB:CC:DD:EE:FF", serviceName = "", pin = ""),
            pair(),
        )
    }

    @Test
    fun `discovery supplies the port, the service name and a fallback name`() {
        val device = pair(certName = " ", discoveredName = "TCL TV", discoveredPort = 7000)
        assertEquals("TCL TV", device.name)
        assertEquals("TCL TV", device.serviceName)
        assertEquals(7000, device.port)
    }

    @Test
    fun `without any name the address is shown`() {
        assertEquals("192.168.1.20", pair(certName = "").name)
    }

    @Test
    fun `pairing again keeps the MAC the user entered and pins afresh`() {
        val previous = PairedDevice("OLD", "TV", "192.168.1.20", 6466, "11:22:33:44:55:66", serviceName = "TCL TV", pin = "PINNED")
        val device = pair(previous = previous)
        assertEquals("11:22:33:44:55:66", device.mac)
        assertEquals("TCL TV", device.serviceName)
        assertEquals("", device.pin)
    }

    @Test
    fun `pairing again fills in a MAC that was missing`() {
        val previous = PairedDevice("OLD", "TV", "192.168.1.20", 6466, mac = "", serviceName = "")
        assertEquals("AA:BB:CC:DD:EE:FF", pair(previous = previous).mac)
    }
}
