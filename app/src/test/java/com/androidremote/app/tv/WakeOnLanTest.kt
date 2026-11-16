package com.androidremote.app.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {

    private val mac = byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E)

    @Test
    fun `magic packet is six FF bytes then the MAC sixteen times`() {
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        for (i in 0 until 16) {
            assertArrayEquals("repeat $i", mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
        }
    }

    @Test
    fun `parseMac accepts the usual notations`() {
        assertArrayEquals(mac, WakeOnLan.parseMac("00:1A:2B:3C:4D:5E"))
        assertArrayEquals(mac, WakeOnLan.parseMac("00-1a-2b-3c-4d-5e"))
        assertArrayEquals(mac, WakeOnLan.parseMac(" 001A2B3C4D5E "))
    }

    @Test
    fun `parseMac rejects anything else`() {
        assertNull(WakeOnLan.parseMac(""))
        assertNull(WakeOnLan.parseMac("00:1A:2B:3C:4D"))
        assertNull(WakeOnLan.parseMac("00:1A:2B:3C:4D:5E:6F"))
        assertNull(WakeOnLan.parseMac("00:1A:2B:3C:4D:ZZ"))
    }

    @Test
    fun `normalizeMac formats as upper-case colon pairs`() {
        assertEquals("00:1A:2B:3C:4D:5E", WakeOnLan.normalizeMac("00-1a-2b-3c-4d-5e"))
        assertNull(WakeOnLan.normalizeMac("not a mac"))
    }

    @Test
    fun `broadcast address sets every host bit`() {
        fun ip(vararg parts: Int) = ByteArray(4) { parts[it].toByte() }
        assertArrayEquals(ip(192, 168, 1, 255), WakeOnLan.broadcastAddress(ip(192, 168, 1, 23), 24))
        assertArrayEquals(ip(10, 255, 255, 255), WakeOnLan.broadcastAddress(ip(10, 1, 2, 3), 8))
        assertArrayEquals(ip(172, 16, 7, 255), WakeOnLan.broadcastAddress(ip(172, 16, 4, 9), 22))
        assertArrayEquals(ip(192, 168, 1, 23), WakeOnLan.broadcastAddress(ip(192, 168, 1, 23), 32))
    }
}
