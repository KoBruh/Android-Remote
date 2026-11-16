package com.androidremote.app.tv

import android.annotation.SuppressLint
import android.net.Network
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** The TV presented a different certificate than the one stored when it was paired. */
class CertMismatchException : CertificateException("TV certificate does not match the paired one")

/** True if this failure was caused by a [CertMismatchException] (it arrives wrapped in an SSL exception). */
fun Throwable.isCertMismatch(): Boolean = generateSequence(this) { it.cause }.any { it is CertMismatchException }

/** An open TLS connection to the TV. */
class TlsConnection(val socket: SSLSocket, val serverCert: X509Certificate) : Closeable {
    val input: InputStream = socket.inputStream
    val output: OutputStream = socket.outputStream

    /** Closes in the background: closing a TLS socket can write to the network, which is not allowed on the main thread. */
    override fun close() {
        closer.execute { runCatching { socket.close() } }
    }

    private companion object {
        val closer = Dispatchers.IO.asExecutor()
    }
}

/**
 * Runs the blocking [block] on the IO dispatcher. If the coroutine is cancelled meanwhile, the
 * receiver is closed, which makes blocked socket reads and writes fail straight away.
 */
suspend fun <T> Closeable.cancellableIo(block: () -> T): T = coroutineScope {
    var finished = false
    val watcher = launch(Dispatchers.IO) {
        try {
            awaitCancellation()
        } finally {
            if (!finished) runCatching { close() }
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: Throwable) {
        // A cancelled read surfaces as an IOException from the closed socket; report cancellation instead.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        finished = true
        watcher.cancel()
    }
}

/**
 * Owns this phone's TLS client identity and opens connections to TVs.
 *
 * The RSA-2048 key pair and its self-signed certificate are generated once inside
 * AndroidKeyStore; the private key never leaves it. TV certificates are trusted on first use
 * and pinned by their SHA-256 fingerprint afterwards.
 */
class CertManager {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    /** This phone's client certificate. Generates the key pair on first use (slow: call off the main thread). */
    @Synchronized
    fun clientCertificate(): X509Certificate {
        if (!keyStore.containsAlias(ALIAS)) generateKeyPair()
        return keyStore.getCertificate(ALIAS) as X509Certificate
    }

    private fun generateKeyPair() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setCertificateSubject(X500Principal("CN=$CLIENT_NAME"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + VALIDITY_DAYS * DAY_MS))
            // TLS client auth signs a ready-made digest, so the key must allow DIGEST_NONE with
            // PKCS#1 padding (TLS 1.2) and raw/PSS operations (TLS 1.3).
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA1,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    /**
     * Opens a TLS connection to the TV, presenting our client certificate.
     *
     * @param pinnedFingerprint the fingerprint stored at pairing, or null to trust whatever the TV presents (first use).
     * @param network the Wi-Fi network to bind to, so the connection never goes over mobile data.
     * @throws javax.net.ssl.SSLException if the handshake fails; check [isCertMismatch] for a changed TV certificate.
     * @throws java.io.IOException if the TV cannot be reached.
     */
    suspend fun connect(host: String, port: Int, pinnedFingerprint: String?, network: Network?): TlsConnection {
        val trustManager = PinnedTrustManager(pinnedFingerprint)
        val raw = Socket()
        return raw.cancellableIo {
            try {
                val context = SSLContext.getInstance("TLS")
                context.init(arrayOf(ClientKeyManager(clientCertificate())), arrayOf(trustManager), null)
                network?.bindSocket(raw)
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                raw.soTimeout = HANDSHAKE_TIMEOUT_MS
                val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                // TLS 1.2 uses PKCS#1 signatures, the best-supported path for AndroidKeyStore keys.
                if (TLS_1_2 in ssl.supportedProtocols) ssl.enabledProtocols = arrayOf(TLS_1_2)
                ssl.startHandshake()
                TlsConnection(ssl, trustManager.serverCert ?: throw SSLException("TV sent no certificate"))
            } catch (e: Throwable) {
                runCatching { raw.close() }
                throw e
            }
        }
    }

    /** Hands our single key and certificate to the TLS stack. */
    private inner class ClientKeyManager(private val certificate: X509Certificate) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = keyStore.getKey(ALIAS, null) as PrivateKey
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    /**
     * Trust on first use: TVs use self-signed certificates, so there is no CA to check against.
     * With a pinned fingerprint only that exact certificate is accepted.
     */
    @SuppressLint("CustomX509TrustManager")
    private class PinnedTrustManager(private val pinnedFingerprint: String?) : X509TrustManager {
        @Volatile
        var serverCert: X509Certificate? = null

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("TV sent no certificate")
            if (pinnedFingerprint != null && !fingerprint(cert).equals(pinnedFingerprint, ignoreCase = true)) {
                throw CertMismatchException()
            }
            serverCert = cert
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Not a server")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        /** Name shown on the TV while pairing. */
        const val CLIENT_NAME = "Android Remote"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "android_remote_client"
        private const val TLS_1_2 = "TLSv1.2"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val VALIDITY_DAYS = 25L * 365
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000

        /** SHA-256 fingerprint of a certificate as upper-case hex. */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        /** Name and MAC address the TV advertises in its certificate. Either may be empty. */
        fun nameAndMac(cert: X509Certificate): Pair<String, String> =
            parseNameAndMac(cert.subjectX500Principal.getName(X500Principal.RFC2253, mapOf(OID_DN_QUALIFIER to "DNQ")))

        /**
         * Extracts the name and MAC address from the subject of an Android TV certificate.
         * Ported from androidtvremote2 (_parse_name_and_mac).
         *
         * NVIDIA SHIELD example: `CN=atvremote/darcy/darcy/SHIELD Android TV/XX:XX:XX:XX:XX:XX`
         * Nexus Player example: `dnQualifier=fugu/fugu/Nexus Player/CN=atvremote/XX:XX:XX:XX:XX:XX`
         *
         * @param subject the subject in RFC 2253 form, with the dnQualifier attribute named `DNQ`.
         */
        fun parseNameAndMac(subject: String): Pair<String, String> {
            val attributes = splitRfc2253(subject)
            val commonName = attributes["CN"].orEmpty()
            val dnQualifier = attributes["DNQ"].orEmpty()
            val commonNameParts = commonName.split("/")
            val name = when {
                dnQualifier.isNotEmpty() -> dnQualifier.split("/").last()
                commonNameParts.size > 1 -> commonNameParts[commonNameParts.size - 2]
                else -> commonName
            }
            return name to commonNameParts.last()
        }

        private const val OID_DN_QUALIFIER = "2.5.4.46"

        /** Splits an RFC 2253 distinguished name into its attributes, honouring backslash escapes. */
        private fun splitRfc2253(subject: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val current = StringBuilder()
            var key: String? = null
            fun flush() {
                key?.let { result.putIfAbsent(it.trim().uppercase(), current.toString()) }
                key = null
                current.clear()
            }
            var i = 0
            while (i < subject.length) {
                val c = subject[i]
                when {
                    c == '\\' && i + 1 < subject.length -> {
                        current.append(subject[i + 1])
                        i++
                    }
                    c == '=' && key == null -> {
                        key = current.toString()
                        current.clear()
                    }
                    c == ',' || c == '+' -> flush()
                    else -> current.append(c)
                }
                i++
            }
            flush()
            return result
        }
    }
}
