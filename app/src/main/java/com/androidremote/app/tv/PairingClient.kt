package com.androidremote.app.tv

import android.net.Network
import com.google.polo.wire.protobuf.PoloProto.Configuration
import com.google.polo.wire.protobuf.PoloProto.Options
import com.google.polo.wire.protobuf.PoloProto.OuterMessage
import com.google.polo.wire.protobuf.PoloProto.PairingRequest
import com.google.polo.wire.protobuf.PoloProto.Secret
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.security.cert.X509Certificate

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
    suspend fun start(host: String, network: Network?): PairingSession =
        withContext(Dispatchers.IO) {
            val connection = certManager.connect(host, PAIRING_PORT, pinnedFingerprint = null, network = network)
            val session = PairingSession(connection, certManager.clientCertificate())
            try {
                session.begin()
            } catch (e: Throwable) {
                session.close()
                throw e
            }
            session
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
