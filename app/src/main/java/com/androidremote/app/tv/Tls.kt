package com.androidremote.app.tv

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
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

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
