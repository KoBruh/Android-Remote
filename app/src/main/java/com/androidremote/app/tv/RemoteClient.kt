package com.androidremote.app.tv

import android.net.Network
import android.os.SystemClock
import com.androidremote.app.proto.RemoteMessage
import com.androidremote.app.proto.remoteKeyInject
import com.androidremote.app.proto.remoteMessage
import com.androidremote.app.proto.remoteVoiceBegin
import com.androidremote.app.proto.remoteVoiceEnd
import com.androidremote.app.proto.remoteVoicePayload
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException

/**
 * Control connection to an Android TV (TLS, port 6466): handshake, ping replies, key
 * injection and voice. Ported from androidtvremote2 (remote.py and androidtv_remote.py).
 *
 * [run] serves one connection; whoever calls it decides when to reconnect.
 */
class RemoteClient(private val certManager: CertManager) {

    private val _state = MutableStateFlow(TvState())

    /** Live state of the TV. `connected` is false whenever there is no usable connection. */
    val state: StateFlow<TvState> = _state.asStateFlow()

    private class Session(val outbox: Channel<RemoteMessage>, val protocol: RemoteProtocol) {
        @Volatile
        var voiceBegin: CompletableDeferred<Int?>? = null
    }

    @Volatile
    private var session: Session? = null

    /**
     * Connects to the TV and serves the connection until it ends. Never returns normally.
     *
     * @param pinnedFingerprint the TV certificate to insist on, or null to accept the one presented.
     * @param onSecured called once the TLS handshake is done, with the fingerprint of the TV's certificate.
     * @throws javax.net.ssl.SSLException if the TLS handshake fails: the TV's certificate changed
     *   (see [isCertMismatch]) or the TV no longer accepts this phone (pair again).
     * @throws java.io.IOException if the TV is unreachable or the connection dropped.
     */
    suspend fun run(
        host: String,
        port: Int,
        pinnedFingerprint: String?,
        network: Network?,
        onSecured: (fingerprint: String) -> Unit = {},
    ): Nothing = withContext(Dispatchers.IO) {
        val connection = certManager.connect(host, port, pinnedFingerprint, network)
        val current = Session(Channel(Channel.UNLIMITED), RemoteProtocol(ENABLE_IME))
        try {
            onSecured(CertManager.fingerprint(connection.serverCert))
            // The TV pings every 5 s when idle. Silence for this long means the connection is
            // dead (e.g. the TV went to deep standby), so give up instead of keeping a zombie.
            connection.socket.soTimeout = IDLE_TIMEOUT_MS
            coroutineScope {
                val writer = launch {
                    for (msg in current.outbox) Framing.write(connection.output, msg.toByteArray())
                }
                session = current
                try {
                    connection.cancellableIo { readLoop(connection, current) }
                } finally {
                    // Close first: it unblocks a writer stuck on a dead connection.
                    connection.close()
                    writer.cancel()
                }
            }
        } finally {
            connection.close()
            current.outbox.close()
            current.voiceBegin?.complete(null)
            // A newer run() may already have taken over; its state must not be wiped.
            if (session === current) {
                session = null
                _state.value = TvState()
            }
        }
    }

    /** Blocking: handles every message of the TV until the connection ends. */
    private fun readLoop(connection: TlsConnection, current: Session): Nothing {
        while (true) {
            val raw = Framing.read(connection.input) ?: throw EOFException("TV closed the connection")
            val msg = try {
                RemoteMessage.parseFrom(raw)
            } catch (_: InvalidProtocolBufferException) {
                continue
            }
            current.protocol.handle(msg)?.let { current.outbox.trySend(it) }
            if (msg.hasRemoteVoiceBegin()) current.voiceBegin?.complete(msg.remoteVoiceBegin.sessionId)
            if (session === current) _state.value = current.protocol.state
        }
    }

    /** Sends a key to the TV. Returns false if there is no connection. */
    fun sendKey(keyCode: Int, direction: KeyDirection = KeyDirection.SHORT): Boolean = send(
        remoteMessage {
            remoteKeyInject = remoteKeyInject {
                keyCodeValue = keyCode
                this.direction = direction.proto
            }
        },
    )

    /**
     * Opens voice search on the TV and streams [recorder] to it while [isHeld] returns true,
     * for at most [maxMs].
     *
     * @return false if the TV did not start a voice session. It then shows its own voice search.
     */
    suspend fun talk(recorder: VoiceRecorder, maxMs: Long, isHeld: () -> Boolean): Boolean {
        val sessionId = startVoice() ?: return false
        try {
            withContext(Dispatchers.IO) {
                val deadline = SystemClock.elapsedRealtime() + maxMs
                while (isHeld() && SystemClock.elapsedRealtime() < deadline) {
                    ensureActive()
                    sendVoice(sessionId, recorder.readChunk() ?: break)
                }
            }
        } finally {
            send(remoteMessage { remoteVoiceEnd = remoteVoiceEnd { this.sessionId = sessionId } })
        }
        return true
    }

    /**
     * Sends KEYCODE_SEARCH, waits for the TV's `remote_voice_begin` and answers it.
     * Ported from androidtvremote2 (start_voice).
     *
     * @return the voice session id, or null if the TV did not start a session.
     */
    private suspend fun startVoice(): Int? {
        val current = session ?: return null
        if (!current.protocol.voiceEnabled) {
            sendKey(KeyCodes.SEARCH)
            return null
        }
        val begin = CompletableDeferred<Int?>()
        current.voiceBegin = begin
        try {
            if (!sendKey(KeyCodes.SEARCH)) return null
            val id = withTimeoutOrNull(VOICE_SESSION_TIMEOUT_MS) { begin.await() } ?: return null
            send(remoteMessage { remoteVoiceBegin = remoteVoiceBegin { sessionId = id } })
            return id
        } finally {
            current.voiceBegin = null
        }
    }

    private fun sendVoice(sessionId: Int, pcm: ByteArray) {
        for (chunk in VoiceFormat.splitChunks(pcm)) {
            send(
                remoteMessage {
                    remoteVoicePayload = remoteVoicePayload {
                        this.sessionId = sessionId
                        samples = ByteString.copyFrom(chunk)
                    }
                },
            )
        }
    }

    private fun send(msg: RemoteMessage): Boolean = session?.outbox?.trySend(msg)?.isSuccess == true

    companion object {
        const val CONTROL_PORT = 6466

        /**
         * Off until the app has a keyboard (Phase 2): with IME on, some TVs hide their on-screen
         * keyboard and expect the text from the phone. The price: the foreground app is not reported.
         */
        private const val ENABLE_IME = false

        private const val IDLE_TIMEOUT_MS = 16_000
        private const val VOICE_SESSION_TIMEOUT_MS = 2_000L
    }
}
