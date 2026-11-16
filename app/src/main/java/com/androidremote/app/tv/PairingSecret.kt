package com.androidremote.app.tv

import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/** The pairing code is malformed or does not belong to this pairing attempt. */
class InvalidPairingCodeException(message: String) : Exception(message)

/**
 * The secret that proves to the TV that the user read the code off its screen.
 * Ported from androidtvremote2 (pairing.py, async_finish_pairing).
 */
object PairingSecret {

    const val CODE_LENGTH = 6

    /**
     * SHA-256 over the client RSA modulus and exponent, the server RSA modulus and exponent and
     * the code's nonce (its last 4 hex digits). The first 2 hex digits of the code are a checksum
     * that must equal the first byte of the hash.
     *
     * @throws InvalidPairingCodeException if the code is not 6 hex digits or its checksum is wrong.
     */
    fun compute(
        clientModulus: BigInteger,
        clientExponent: BigInteger,
        serverModulus: BigInteger,
        serverExponent: BigInteger,
        code: String,
    ): ByteArray {
        if (code.length != CODE_LENGTH) throw InvalidPairingCodeException("Code must be $CODE_LENGTH characters")
        if (!code.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            throw InvalidPairingCodeException("Code must be hexadecimal")
        }
        val codeBytes = ByteArray(CODE_LENGTH / 2) { code.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsignedBytes(clientModulus))
        digest.update(unsignedBytes(clientExponent))
        digest.update(unsignedBytes(serverModulus))
        digest.update(unsignedBytes(serverExponent))
        digest.update(codeBytes, 1, codeBytes.size - 1)
        val hash = digest.digest()
        if (hash[0] != codeBytes[0]) throw InvalidPairingCodeException("Wrong code")
        return hash
    }

    fun compute(client: X509Certificate, server: X509Certificate, code: String): ByteArray {
        val clientKey = client.publicKey as RSAPublicKey
        val serverKey = server.publicKey as? RSAPublicKey ?: throw IOException("TV certificate is not RSA")
        return compute(clientKey.modulus, clientKey.publicExponent, serverKey.modulus, serverKey.publicExponent, code)
    }

    /** Big-endian bytes of a positive integer without a sign byte (Python: `int.to_bytes`). Zero is empty. */
    fun unsignedBytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else if (value.signum() == 0) ByteArray(0) else bytes
    }
}
