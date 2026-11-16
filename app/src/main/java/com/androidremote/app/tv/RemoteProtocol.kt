package com.androidremote.app.tv

import com.androidremote.app.proto.RemoteMessage
import com.androidremote.app.proto.remoteConfigure
import com.androidremote.app.proto.remoteDeviceInfo
import com.androidremote.app.proto.remoteMessage
import com.androidremote.app.proto.remotePingResponse
import com.androidremote.app.proto.remoteSetActive

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

/** What the TV has told us over the current connection. Reset when the connection drops. */
data class TvState(
    /** The control handshake is done and the TV accepts keys. */
    val connected: Boolean = false,
    /** Power state from RemoteStart. Null until the TV has reported it. */
    val isOn: Boolean? = null,
    val volume: VolumeInfo? = null,
    /** Package name of the app in the foreground, if the TV reports it (needs the IME feature). */
    val currentApp: String = "",
    val vendor: String = "",
    val model: String = "",
)

/**
 * The control protocol's message handling, free of any I/O so it can be unit tested.
 * Ported from androidtvremote2 (remote.py, RemoteProtocol._handle_message).
 *
 * @param enableIme IME support reports the foreground app and allows typing, but makes some
 *   TVs hide their on-screen keyboard and expect the text from the phone.
 */
internal class RemoteProtocol(enableIme: Boolean) {

    var state = TvState()
        private set

    /** Features both sides support. Narrowed once the TV sends its own list. */
    var activeFeatures: Int =
        FEATURE_PING or FEATURE_KEY or FEATURE_VOICE or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK or
            (if (enableIme) FEATURE_IME else 0)
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
