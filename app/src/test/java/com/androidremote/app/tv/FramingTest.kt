package com.androidremote.app.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class FramingTest {

    @Test
    fun `varint encodes like protobuf`() {
        assertArrayEquals(byteArrayOf(0), Framing.encodeVarint(0))
        assertArrayEquals(byteArrayOf(1), Framing.encodeVarint(1))
        assertArrayEquals(byteArrayOf(127), Framing.encodeVarint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), Framing.encodeVarint(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), Framing.encodeVarint(300))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), Framing.encodeVarint(16384))
    }

    @Test
    fun `frame prefixes the payload with its length`() {
        assertArrayEquals(byteArrayOf(3, 10, 20, 30), Framing.frame(byteArrayOf(10, 20, 30)))
        assertArrayEquals(byteArrayOf(0), Framing.frame(ByteArray(0)))
    }

    @Test
    fun `read returns every message of a stream in order`() {
        val big = ByteArray(300) { it.toByte() }
        val input = ByteArrayInputStream(Framing.frame(byteArrayOf(1, 2)) + Framing.frame(ByteArray(0)) + Framing.frame(big))
        assertArrayEquals(byteArrayOf(1, 2), Framing.read(input))
        assertArrayEquals(ByteArray(0), Framing.read(input))
        assertArrayEquals(big, Framing.read(input))
        assertNull(Framing.read(input))
    }

    @Test
    fun `write then read round trips`() {
        val out = ByteArrayOutputStream()
        val payload = ByteArray(20_000) { (it * 7).toByte() }
        Framing.write(out, payload)
        assertEquals(payload.size + 3, out.size())
        assertArrayEquals(payload, Framing.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `read reports a stream that ends inside a message`() {
        assertThrows(EOFException::class.java) { Framing.read(ByteArrayInputStream(byteArrayOf(5, 1, 2))) }
        assertThrows(EOFException::class.java) { Framing.read(ByteArrayInputStream(byteArrayOf(0x80.toByte()))) }
    }

    @Test
    fun `read rejects corrupt and oversized lengths`() {
        val endless = ByteArray(6) { 0xFF.toByte() }
        assertThrows(IOException::class.java) { Framing.read(ByteArrayInputStream(endless)) }
        val tooLarge = Framing.encodeVarint(Framing.MAX_MESSAGE_SIZE + 1)
        assertThrows(IOException::class.java) { Framing.read(ByteArrayInputStream(tooLarge)) }
    }
}
