package com.androidremote.app.tv

import com.androidremote.app.proto.remoteConfigure
import com.androidremote.app.proto.remoteDeviceInfo
import com.androidremote.app.proto.remoteMessage
import com.androidremote.app.proto.remotePingRequest
import com.androidremote.app.proto.remoteSetActive
import com.androidremote.app.proto.remoteSetVolumeLevel
import com.androidremote.app.proto.remoteStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {

    private val allFeatures = 0x3FF

    @Test
    fun `every ping is answered with the same value`() {
        val protocol = RemoteProtocol(enableIme = false, enableVoice = true)
        val reply = protocol.handle(remoteMessage { remotePingRequest = remotePingRequest { val1 = 42 } })
        assertTrue(reply!!.hasRemotePingResponse())
        assertEquals(42, reply.remotePingResponse.val1)
    }

    @Test
    fun `configure is answered with the features both sides support`() {
        val protocol = RemoteProtocol(enableIme = false, enableVoice = true)
        val tvFeatures = RemoteProtocol.FEATURE_PING or RemoteProtocol.FEATURE_KEY or RemoteProtocol.FEATURE_IME or
            RemoteProtocol.FEATURE_POWER or RemoteProtocol.FEATURE_VOLUME
        val reply = protocol.handle(
            remoteMessage {
                remoteConfigure = remoteConfigure {
                    code1 = tvFeatures
                    deviceInfo = remoteDeviceInfo {
                        vendor = "TCL"
                        model = "Smart TV"
                    }
                }
            },
        )!!
        val expected = RemoteProtocol.FEATURE_PING or RemoteProtocol.FEATURE_KEY or RemoteProtocol.FEATURE_POWER or
            RemoteProtocol.FEATURE_VOLUME
        assertEquals(expected, reply.remoteConfigure.code1)
        assertEquals("atvremote", reply.remoteConfigure.deviceInfo.packageName)
        assertEquals("TCL", protocol.state.vendor)
        assertEquals("Smart TV", protocol.state.model)
        assertFalse("TV has no voice support", protocol.voiceEnabled)
    }

    @Test
    fun `set active is answered with the active features and completes the handshake`() {
        val protocol = RemoteProtocol(enableIme = false, enableVoice = true)
        protocol.handle(remoteMessage { remoteConfigure = remoteConfigure { code1 = allFeatures } })
        assertFalse(protocol.state.connected)
        val reply = protocol.handle(remoteMessage { remoteSetActive = remoteSetActive { active = allFeatures } })!!
        assertEquals(protocol.activeFeatures, reply.remoteSetActive.active)
        assertTrue(protocol.state.connected)
        assertTrue(protocol.voiceEnabled)
        assertEquals("IME stays off", 0, protocol.activeFeatures and RemoteProtocol.FEATURE_IME)
    }

    @Test
    fun `power state and volume are tracked`() {
        val protocol = RemoteProtocol(enableIme = false, enableVoice = true)
        assertNull(protocol.state.isOn)
        assertNull(protocol.handle(remoteMessage { remoteStart = remoteStart { started = false } }))
        assertEquals(false, protocol.state.isOn)
        protocol.handle(remoteMessage { remoteStart = remoteStart { started = true } })
        assertEquals(true, protocol.state.isOn)
        protocol.handle(
            remoteMessage {
                remoteSetVolumeLevel = remoteSetVolumeLevel {
                    volumeLevel = 12
                    volumeMax = 100
                    volumeMuted = true
                }
            },
        )
        assertEquals(VolumeInfo(level = 12, max = 100, muted = true), protocol.state.volume)
    }

    @Test
    fun `voice chunks are split and padded to what the TV accepts`() {
        val max = RemoteClient.VOICE_CHUNK_SIZE
        val min = RemoteClient.VOICE_CHUNK_MIN_SIZE
        assertEquals(listOf(min), RemoteClient.splitVoiceChunks(ByteArray(100)).map { it.size })
        assertEquals(listOf(max, max, min), RemoteClient.splitVoiceChunks(ByteArray(2 * max + 1)).map { it.size })
        assertEquals(listOf(max), RemoteClient.splitVoiceChunks(ByteArray(max)).map { it.size })
        assertTrue(RemoteClient.splitVoiceChunks(ByteArray(0)).isEmpty())
    }
}
