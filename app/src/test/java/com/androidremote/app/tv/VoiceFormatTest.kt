package com.androidremote.app.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFormatTest {

    private val max = VoiceFormat.CHUNK_SIZE
    private val min = VoiceFormat.CHUNK_MIN_SIZE

    @Test
    fun `short audio is padded with silence to the minimum chunk size`() {
        val chunks = VoiceFormat.splitChunks(ByteArray(100) { 7 })
        assertEquals(listOf(min), chunks.map { it.size })
        assertArrayEquals(ByteArray(100) { 7 }, chunks[0].copyOf(100))
        assertTrue(chunks[0].drop(100).all { it == 0.toByte() })
    }

    @Test
    fun `long audio is split at the maximum chunk size`() {
        assertEquals(listOf(max, max, min), VoiceFormat.splitChunks(ByteArray(2 * max + 1)).map { it.size })
        assertEquals(listOf(max), VoiceFormat.splitChunks(ByteArray(max)).map { it.size })
        assertEquals(listOf(max, min + 5), VoiceFormat.splitChunks(ByteArray(max + min + 5)).map { it.size })
    }

    @Test
    fun `no audio gives no chunks`() {
        assertTrue(VoiceFormat.splitChunks(ByteArray(0)).isEmpty())
    }
}
