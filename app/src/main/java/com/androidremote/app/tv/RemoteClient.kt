package com.androidremote.app.tv

import android.net.Network
import com.androidremote.app.proto.RemoteDirection
import com.androidremote.app.proto.RemoteMessage
import com.androidremote.app.proto.remoteConfigure
import com.androidremote.app.proto.remoteDeviceInfo
import com.androidremote.app.proto.remoteKeyInject
import com.androidremote.app.proto.remoteMessage
import com.androidremote.app.proto.remotePingResponse
import com.androidremote.app.proto.remoteSetActive
import com.androidremote.app.proto.remoteVoiceBegin
import com.androidremote.app.proto.remoteVoiceEnd
import com.androidremote.app.proto.remoteVoicePayload
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException

/** Android KeyEvent codes sent to the TV. Any other KeyEvent code works too. */
object KeyCodes {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val SEARCH = 84
    const val VOLUME_MUTE = 164
}

/** How a key is pressed: a tap, or the start and end of a hold. */
enum class KeyDirection(internal val proto: RemoteDirection) {
    SHORT(RemoteDirection.SHORT),
    START_LONG(RemoteDirection.START_LONG),
    END_LONG(RemoteDirection.END_LONG),
}

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

/** What the TV has told us over the current connection. Reset when the connection drops. */
data class TvState(
    /** The control handshake is done and the TV accepts keys. */
    val connected: Boolean = false,
    /** Power state from RemoteStart. Null until the TV has reported it. */
    val isOn: Boolean? = null,
    val volume: VolumeInfo? = null,
    /** Package name of the app in the foreground, if the TV reports it. */
    val currentApp: String = "",
    val vendor: String = "",
    val model: String = "",
)

/**
 * The control protocol's message handling, free of any I/O so it can be unit tested.
 * Ported from androidtvremote2 (remote.py, RemoteProtocol._handle_message).
 */
internal class RemoteProtocol(enableIme: Boolean, enableVoice: Boolean) {

    var state = TvState()
        private set

    /** Features both sides support. Narrowed once the TV sends its own list. */
    var activeFeatures: Int =
        FEATURE_PING or FEATURE_KEY or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK or
            (if (enableIme) FEATURE_IME else 0) or (if (enableVoice) FEATURE_VOICE else 0)
        private set

    val voiceEnabled: Boolean get() = activeFeatures and FEATURE_VOICE != 0

    /** Updates [state] from a message of the TV and returns the reply to send, if any. */
    fun handle(msg: RemoteMessage): RemoteMessage? = when {
        msg.hasRemoteConfigure() -> {
            val config = msg.remoteConfigure
            state = state.copy(vendor = config.deviceInfo.vendor, model = config.deviceInfo.model)
            activeFeatures = activeFeatures and config.code1
            remoteMessage {
                remoteConfigure = remoteConfigure {
                    code1 = activeFeatures
                    deviceInfo = remoteDeviceInfo {
                        unknown1 = 1
                        unknown2 = "1"
                        packageName = "atvremote"
                        appVersion = "1.0.0"
                    }
                }
            }
        }
        msg.hasRemoteSetActive() -> {
            state = state.copy(connected = true)
            remoteMessage { remoteSetActive = remoteSetActive { active = activeFeatures } }
        }
        msg.hasRemoteImeKeyInject() -> {
            state = state.copy(currentApp = msg.remoteImeKeyInject.appInfo.appPackage)
            null
        }
        msg.hasRemoteSetVolumeLevel() -> {
            val volume = msg.remoteSetVolumeLevel
            state = state.copy(volume = VolumeInfo(volume.volumeLevel, volume.volumeMax, volume.volumeMuted))
            null
        }
        msg.hasRemoteStart() -> {
            state = state.copy(connected = true, isOn = msg.remoteStart.started)
            null
        }
        // The TV drops the connection after a few unanswered pings.
        msg.hasRemotePingRequest() -> remoteMessage {
            remotePingResponse = remotePingResponse { val1 = msg.remotePingRequest.val1 }
        }
        else -> null
    }

    companion object {
        const val FEATURE_PING = 1 shl 0
        const val FEATURE_KEY = 1 shl 1
        const val FEATURE_IME = 1 shl 2

        /** Makes the TV answer KEYCODE_SEARCH with remote_voice_begin. */
        const val FEATURE_VOICE = 1 shl 3
        const val FEATURE_POWER = 1 shl 5
        const val FEATURE_VOLUME = 1 shl 6
        const val FEATURE_APP_LINK = 1 shl 9
    }
}

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
     * @throws javax.net.ssl.SSLException if the TLS handshake fails: the TV's certificate changed
     *   (see [isCertMismatch]) or the TV no longer accepts this phone (pair again).
     * @throws java.io.IOException if the TV is unreachable or the connection dropped.
     */
    suspend fun run(host: String, port: Int, pinnedFingerprint: String, network: Network?): Nothing =
        withContext(Dispatchers.IO) {
            val connection = certManager.connect(host, port, pinnedFingerprint, network)
            val current = Session(Channel(Channel.UNLIMITED), RemoteProtocol(ENABLE_IME, ENABLE_VOICE))
            try {
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
                if (session === current) session = null
                connection.close()
                current.outbox.close()
                current.voiceBegin?.complete(null)
                _state.value = TvState()
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
            _state.value = current.protocol.state
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
     * Opens voice search on the TV and asks it for a voice session.
     *
     * Sends KEYCODE_SEARCH and waits for the TV's `remote_voice_begin`.
     *
     * @return the session id for [sendVoice] and [endVoice], or null if the TV did not start a
     *   session. In that case the TV still shows its own voice search.
     */
    suspend fun startVoice(): Int? {
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

    /** Sends 16-bit mono 8 kHz PCM audio for a voice session. */
    fun sendVoice(sessionId: Int, pcm: ByteArray) {
        for (chunk in splitVoiceChunks(pcm)) {
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

    fun endVoice(sessionId: Int) {
        send(remoteMessage { remoteVoiceEnd = remoteVoiceEnd { this.sessionId = sessionId } })
    }

    private fun send(msg: RemoteMessage): Boolean = session?.outbox?.trySend(msg)?.isSuccess == true

    companion object {
        const val CONTROL_PORT = 6466

        /**
         * IME support tells us the foreground app, but makes some TVs hide their on-screen
         * keyboard and expect typing from the phone. Off until the app has a keyboard (Phase 2).
         */
        private const val ENABLE_IME = false
        private const val ENABLE_VOICE = true

        private const val IDLE_TIMEOUT_MS = 16_000
        private const val VOICE_SESSION_TIMEOUT_MS = 2_000L

        /** Largest voice chunk; the TV closes the connection on bigger ones. */
        const val VOICE_CHUNK_SIZE = 20 * 1024

        /** Smallest voice chunk; smaller ones are padded with silence (needed by e.g. Shield TV). */
        const val VOICE_CHUNK_MIN_SIZE = 8 * 1024

        /** Splits PCM audio into chunks the TV accepts. */
        fun splitVoiceChunks(pcm: ByteArray): List<ByteArray> =
            (pcm.indices step VOICE_CHUNK_SIZE).map { start ->
                val chunk = pcm.copyOfRange(start, minOf(start + VOICE_CHUNK_SIZE, pcm.size))
                if (chunk.size < VOICE_CHUNK_MIN_SIZE) chunk.copyOf(VOICE_CHUNK_MIN_SIZE) else chunk
            }
    }
}
