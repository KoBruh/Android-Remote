package com.androidremote.app.tv

import android.net.Network
import com.google.polo.wire.protobuf.PoloProto.Configuration
import com.google.polo.wire.protobuf.PoloProto.Options
import com.google.polo.wire.protobuf.PoloProto.OuterMessage
import com.google.polo.wire.protobuf.PoloProto.PairingRequest
import com.google.polo.wire.protobuf.PoloProto.Secret
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import java.io.Closeable
import java.io.EOFException
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
        val codeBytes = try {
            ByteArray(CODE_LENGTH / 2) { code.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            throw InvalidPairingCodeException("Code must be hexadecimal")
        }
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

/**
 * Pairing protocol ("polo") with an Android TV, over TLS on port 6467.
 * Ported from androidtvremote2 (pairing.py). Messages are [OuterMessage]s, see polo.proto.
 */
class PairingClient(private val certManager: CertManager) {

    /**
     * Connects and runs the handshake up to the point where the TV shows its 6-character code.
     *
     * @throws IOException if the TV cannot be reached or rejects the request.
     */
    suspend fun start(host: String, network: Network?, port: Int = PAIRING_PORT): PairingSession {
        val connection = certManager.connect(host, port, pinnedFingerprint = null, network = network)
        val session = PairingSession(connection, certManager.clientCertificate())
        try {
            session.begin()
        } catch (e: Throwable) {
            session.close()
            throw e
        }
        return session
    }

    companion object {
        const val PAIRING_PORT = 6467
    }
}

/** A pairing attempt in progress: the TV is showing its code and waits for [finish]. */
class PairingSession internal constructor(
    private val connection: TlsConnection,
    private val clientCert: X509Certificate,
) : Closeable {

    /** The certificate the TV presented. Its fingerprint is pinned once pairing succeeds. */
    val serverCert: X509Certificate get() = connection.serverCert

    internal suspend fun begin() {
        connection.socket.soTimeout = RESPONSE_TIMEOUT_MS

        val ack = exchange(newMessage().setPairingRequest(
            PairingRequest.newBuilder()
                .setServiceName(SERVICE_NAME)
                .setClientName(CertManager.CLIENT_NAME),
        ))
        if (!ack.hasPairingRequestAck()) throw IOException("Unexpected reply to the pairing request")

        val encoding = Options.Encoding.newBuilder()
            .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(PairingSecret.CODE_LENGTH)
        val options = exchange(newMessage().setOptions(
            Options.newBuilder()
                .setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT)
                .addInputEncodings(encoding),
        ))
        if (!options.hasOptions()) throw IOException("Unexpected reply to the pairing options")

        val configured = exchange(newMessage().setConfiguration(
            Configuration.newBuilder()
                .setClientRole(Options.RoleType.ROLE_TYPE_INPUT)
                .setEncoding(encoding),
        ))
        if (!configured.hasConfigurationAck()) throw IOException("Unexpected reply to the pairing configuration")
    }

    /**
     * Sends the secret derived from the code shown on the TV.
     *
     * @throws InvalidPairingCodeException if the code is wrong. The session stays open for another try.
     * @throws IOException if the TV rejected the secret or closed the connection (e.g. Cancel pressed on the TV).
     */
    suspend fun finish(code: String) {
        val secret = PairingSecret.compute(clientCert, serverCert, code.trim())
        val ack = exchange(newMessage().setSecret(
            Secret.newBuilder()
                .setSecret(ByteString.copyFrom(secret)),
        ))
        if (!ack.hasSecretAck()) throw IOException("Unexpected reply to the pairing secret")
    }

    private suspend fun exchange(message: OuterMessage.Builder): OuterMessage = connection.cancellableIo {
        Framing.write(connection.output, message.build().toByteArray())
        val raw = Framing.read(connection.input) ?: throw EOFException("TV closed the connection")
        val reply = try {
            OuterMessage.parseFrom(raw)
        } catch (e: InvalidProtocolBufferException) {
            throw IOException("Unreadable reply from the TV", e)
        }
        if (reply.status != OuterMessage.Status.STATUS_OK) throw IOException("TV answered with ${reply.status}")
        reply
    }

    private fun newMessage(): OuterMessage.Builder =
        OuterMessage.newBuilder().setProtocolVersion(PROTOCOL_VERSION).setStatus(OuterMessage.Status.STATUS_OK)

    override fun close() = connection.close()

    private companion object {
        const val SERVICE_NAME = "atvremote"
        const val PROTOCOL_VERSION = 2

        /** Every pairing step is answered by the TV itself, so this never waits on a human. */
        const val RESPONSE_TIMEOUT_MS = 10_000
    }
}
