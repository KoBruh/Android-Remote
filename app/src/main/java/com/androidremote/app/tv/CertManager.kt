package com.androidremote.app.tv

import android.annotation.SuppressLint
import android.net.Network
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
    }
}
