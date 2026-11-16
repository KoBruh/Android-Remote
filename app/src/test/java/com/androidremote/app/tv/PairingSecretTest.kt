package com.androidremote.app.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

class PairingSecretTest {

    // Same inputs as androidtvremote2's
    // test_finish_pairing_handles_odd_hex_length_modulus_and_even_hex_exponent:
    // modulus 0xABC and exponent 0x11 for both sides, nonce "ABCD". The expected hash was
    // computed with that test's own code (Python hashlib).
    private val modulus = BigInteger("ABC", 16)
    private val exponent = BigInteger("11", 16)
    private val expected = hex("1688846fd5cafaa650e862e17f7f3cc1665c0b7ff5e5f0e4793609cda38ba22d")

    @Test
    fun `secret matches the androidtvremote2 vector`() {
        assertArrayEquals(expected, PairingSecret.compute(modulus, exponent, modulus, exponent, "16ABCD"))
    }

    @Test
    fun `code is case insensitive`() {
        assertArrayEquals(expected, PairingSecret.compute(modulus, exponent, modulus, exponent, "16abcd"))
    }

    @Test
    fun `a code whose checksum does not match is rejected`() {
        assertThrows(InvalidPairingCodeException::class.java) {
            PairingSecret.compute(modulus, exponent, modulus, exponent, "17ABCD")
        }
    }

    @Test
    fun `a code must be six hex digits`() {
        for (code in listOf("", "12345", "1234567", "12345G")) {
            assertThrows(code, InvalidPairingCodeException::class.java) {
                PairingSecret.compute(modulus, exponent, modulus, exponent, code)
            }
        }
    }

    @Test
    fun `integers are hashed as minimal big-endian bytes`() {
        assertArrayEquals(byteArrayOf(0x0A, 0xBC.toByte()), PairingSecret.unsignedBytes(modulus))
        assertArrayEquals(byteArrayOf(0x11), PairingSecret.unsignedBytes(exponent))
        // 0x80 needs a sign byte in BigInteger.toByteArray(); it must not end up in the hash.
        assertArrayEquals(byteArrayOf(0x80.toByte()), PairingSecret.unsignedBytes(BigInteger.valueOf(128)))
        assertArrayEquals(byteArrayOf(1, 0, 1), PairingSecret.unsignedBytes(BigInteger.valueOf(65537)))
        assertEquals(0, PairingSecret.unsignedBytes(BigInteger.ZERO).size)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
