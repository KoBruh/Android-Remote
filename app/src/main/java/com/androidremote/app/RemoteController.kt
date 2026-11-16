package com.androidremote.app

import android.net.Network
import android.os.SystemClock
import androidx.annotation.StringRes
import com.androidremote.app.data.DeviceStore
import com.androidremote.app.data.PairedDevice
import com.androidremote.app.data.Settings
import com.androidremote.app.data.StoredData
import com.androidremote.app.tv.CertManager
import com.androidremote.app.tv.DiscoveredTv
import com.androidremote.app.tv.Discovery
import com.androidremote.app.tv.KeyCodes
import com.androidremote.app.tv.KeyDirection
import com.androidremote.app.tv.NetworkMonitor
import com.androidremote.app.tv.PairingClient
import com.androidremote.app.tv.PairingSession
import com.androidremote.app.tv.RemoteClient
import com.androidremote.app.tv.TvState
import com.androidremote.app.tv.VoiceRecorder
import com.androidremote.app.tv.WakeOnLan
import com.androidremote.app.tv.isCertMismatch
import com.androidremote.app.ui.remote.PressEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

enum class LinkState { Disconnected, Connecting, Connected }

/** Why the app suggests pairing again. */
enum class RepairReason {
    /** The TV presents a different certificate than at pairing (e.g. after a factory reset). */
    CertChanged,

    /** The TV refuses this phone's certificate (e.g. its remote service data was cleared). */
    Rejected,
}

data class RepairPrompt(val device: PairedDevice, val reason: RepairReason)

/** A short status message. [id] makes two identical messages in a row distinguishable. */
data class UiMessage(@StringRes val text: Int, val id: Long)

/** Everything the UI shows. */
data class RemoteUiState(
    /** Stored devices and settings have been read from disk. */
    val loaded: Boolean = false,
    val devices: List<PairedDevice> = emptyList(),
    /** The TV the remote controls. */
    val selected: PairedDevice? = null,
    /** TVs found on the network that are not paired yet. */
    val discovered: List<DiscoveredTv> = emptyList(),
    val tv: TvState = TvState(),
    /** A first connection attempt or a wake-up is running: the pill pulses. */
    val busy: Boolean = false,
    /** The phone microphone is streaming to the TV. */
    val micActive: Boolean = false,
    val settings: Settings = Settings(),
    val message: UiMessage? = null,
    val repairPrompt: RepairPrompt? = null,
) {
    val link: LinkState
        get() = when {
            tv.connected -> LinkState.Connected
            busy -> LinkState.Connecting
            else -> LinkState.Disconnected
        }
}

/**
 * The app's single source of truth and the only thing the UI talks to.
 *
 * It keeps the connection to the selected TV alive while the app is in the foreground,
 * implements the power/wake logic and voice, and exposes one [state] flow. It lives for the
 * whole process (see [AppContainer]), so a Quick Settings tile, widget or notification can
 * drive the same instance later.
 */
class RemoteController(
    private val scope: CoroutineScope,
    private val store: DeviceStore,
    private val client: RemoteClient,
    private val pairing: PairingClient,
    private val discovery: Discovery,
    private val networks: NetworkMonitor,
) {
    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    private var foreground = false
    private var network: Network? = null
    private var allDiscovered: List<DiscoveredTv> = emptyList()

    private var linkJob: Job? = null
    private var foregroundJob: Job? = null
    private var wakeJob: Job? = null
    private var voiceJob: Job? = null

    /** A wake-up is running: retry every second instead of backing off. */
    private var waking = false

    /** The TV accepted keys at some point during the current connection attempt. */
    private var connectedThisAttempt = false

    @Volatile
    private var micHeld = false
    private var messageCounter = 0L

    init {
        scope.launch { store.data.collect(::onStored) }
        scope.launch {
            client.state.collect { tv ->
                if (tv.connected) connectedThisAttempt = true
                _state.update { it.copy(tv = tv, busy = it.busy && !tv.connected) }
            }
        }
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    /** Call when the app comes to the foreground or leaves it. The TV connection only lives in the foreground. */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            network = networks.currentWifi()
            foregroundJob = scope.launch {
                launch {
                    networks.wifiNetworks().collect { wifi ->
                        if (wifi != network) {
                            network = wifi
                            if (wifi != null) restartLink()
                        }
                    }
                }
                launch { discovery.discover().collect(::onDiscovered) }
            }
        } else {
            foregroundJob?.cancel()
            wakeJob?.cancel()
            micHeld = false
            allDiscovered = emptyList()
            _state.update { it.copy(discovered = emptyList()) }
        }
        restartLink()
    }

    /** Drops the current connection attempt and tries again straight away. */
    fun reconnectNow() = restartLink()

    private fun onStored(data: StoredData) {
        val old = _state.value.selected
        val new = data.selected
        _state.update {
            it.copy(
                loaded = true,
                devices = data.devices,
                selected = new,
                settings = data.settings,
                discovered = unpaired(allDiscovered, data.devices),
            )
        }
        if (old?.fingerprint != new?.fingerprint || old?.host != new?.host || old?.port != new?.port) restartLink()
    }

    private fun onDiscovered(found: List<DiscoveredTv>) {
        allDiscovered = found
        val devices = _state.value.devices
        _state.update { it.copy(discovered = unpaired(found, devices)) }
        // Follow paired TVs whose IP address changed (DHCP).
        for (tv in found) {
            val device = devices.firstOrNull { it.serviceName.isNotEmpty() && it.serviceName == tv.name } ?: continue
            if (device.host != tv.host || device.port != tv.port) {
                scope.launch { store.update(device.copy(host = tv.host, port = tv.port)) }
            }
        }
    }

    private fun unpaired(found: List<DiscoveredTv>, devices: List<PairedDevice>): List<DiscoveredTv> =
        found.filter { tv -> devices.none { it.serviceName == tv.name || it.host == tv.host } }

    // ---- Connection ----------------------------------------------------------------------------

    private fun restartLink() {
        linkJob?.cancel()
        val device = _state.value.selected
        if (!foreground || device == null) {
            _state.update { it.copy(busy = false) }
            return
        }
        linkJob = scope.launch { runLink(device) }
    }

    /** Keeps connecting to [device] until cancelled, or until pairing again is the only way forward. */
    private suspend fun runLink(device: PairedDevice) {
        var backoffMs = MIN_BACKOFF_MS
        var sslFailures = 0
        _state.update { it.copy(busy = true) }
        while (true) {
            connectedThisAttempt = false
            try {
                client.run(device.host, device.port, device.fingerprint, network)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else (unreachable TV, dropped connection, missing network permission) is retried.
                coroutineContext.ensureActive()
                if (connectedThisAttempt) {
                    backoffMs = MIN_BACKOFF_MS
                    sslFailures = 0
                } else if (e.isCertMismatch()) {
                    stopForRepair(device, RepairReason.CertChanged)
                    return
                } else if (e is SSLException && ++sslFailures >= SSL_FAILURES_BEFORE_PROMPT) {
                    stopForRepair(device, RepairReason.Rejected)
                    return
                }
            }
            // Only the first attempt and a wake-up pulse; later retries run quietly.
            if (!waking) _state.update { it.copy(busy = false) }
            delay(if (waking) WAKE_RETRY_MS else backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun stopForRepair(device: PairedDevice, reason: RepairReason) {
        _state.update { it.copy(busy = false, repairPrompt = RepairPrompt(device, reason)) }
    }

    fun dismissRepairPrompt() = _state.update { it.copy(repairPrompt = null) }

    // ---- Keys ----------------------------------------------------------------------------------

    /** Translates what a button reports into key messages for the TV. */
    fun press(keyCode: Int, event: PressEvent) {
        when (event) {
            PressEvent.Tap, PressEvent.Repeat -> client.sendKey(keyCode, KeyDirection.SHORT)
            PressEvent.LongStart -> client.sendKey(keyCode, KeyDirection.START_LONG)
            PressEvent.LongEnd -> client.sendKey(keyCode, KeyDirection.END_LONG)
            PressEvent.Down, PressEvent.Up -> Unit
        }
    }

    fun sendKey(keyCode: Int) {
        client.sendKey(keyCode)
    }

    // ---- Power and wake ------------------------------------------------------------------------

    /**
     * Power button. Connected: toggles power. Not reachable: wakes the TV with Wake-on-LAN,
     * reconnects, and only sends POWER if the TV still reports that it is off.
     */
    fun power() {
        if (_state.value.tv.connected) {
            client.sendKey(KeyCodes.POWER)
            return
        }
        val device = _state.value.selected
        if (device == null) {
            show(R.string.msg_no_tv)
            return
        }
        if (wakeJob?.isActive == true) return
        wakeJob = scope.launch {
            waking = true
            try {
                val mac = WakeOnLan.parseMac(device.mac)
                if (mac == null) {
                    show(R.string.msg_no_mac)
                } else {
                    val wifi = network
                    WakeOnLan.send(mac) { socket -> wifi?.bindSocket(socket) }
                }
                restartLink()
                val connected = withTimeoutOrNull(WAKE_TIMEOUT_MS) { client.state.first { it.connected } }
                if (connected == null) {
                    if (mac != null) show(R.string.msg_wake_failed)
                    return@launch
                }
                // The TV may report "off" for a moment while it wakes; give it time to settle so
                // POWER is not sent to a TV that is already coming up (that would turn it off again).
                withTimeoutOrNull(WAKE_SETTLE_MS) { client.state.first { !it.connected || it.isOn == true } }
                val tv = client.state.value
                if (tv.connected && tv.isOn == false) client.sendKey(KeyCodes.POWER)
            } finally {
                waking = false
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ---- Voice ---------------------------------------------------------------------------------

    /**
     * Mic button pressed. With the microphone permission the phone streams audio to the TV for
     * as long as the button is held. Without it, the TV just opens its own voice search.
     */
    fun micPressed(hasMicPermission: Boolean) {
        if (!_state.value.tv.connected) return
        if (!hasMicPermission) {
            client.sendKey(KeyCodes.SEARCH)
            return
        }
        if (voiceJob?.isActive == true) return
        micHeld = true
        voiceJob = scope.launch {
            val recorder = VoiceRecorder.open()
            if (recorder == null) {
                client.sendKey(KeyCodes.SEARCH)
                return@launch
            }
            try {
                _state.update { it.copy(micActive = true) }
                val sessionId = client.startVoice() ?: return@launch
                try {
                    withContext(Dispatchers.IO) {
                        val deadline = SystemClock.elapsedRealtime() + MAX_VOICE_MS
                        while (micHeld && SystemClock.elapsedRealtime() < deadline) {
                            ensureActive()
                            client.sendVoice(sessionId, recorder.readChunk() ?: break)
                        }
                    }
                } finally {
                    client.endVoice(sessionId)
                }
            } finally {
                recorder.close()
                _state.update { it.copy(micActive = false) }
            }
        }
    }

    fun micReleased() {
        micHeld = false
    }

    // ---- Pairing -------------------------------------------------------------------------------

    /**
     * Starts pairing with the TV at [host]: the TV shows its code.
     *
     * @throws IOException if the TV cannot be reached.
     */
    suspend fun startPairing(host: String): PairingSession = pairing.start(host, network)

    /**
     * Finishes pairing with the code from the TV's screen, then saves and selects the TV.
     *
     * @param discovered the discovery entry the pairing was started from, if any.
     * @throws com.androidremote.app.tv.InvalidPairingCodeException if the code is wrong (try again).
     * @throws IOException if the TV rejected the code or closed the connection.
     */
    suspend fun finishPairing(session: PairingSession, code: String, host: String, discovered: DiscoveredTv?) {
        session.finish(code)
        session.close()
        val fingerprint = CertManager.fingerprint(session.serverCert)
        val (certName, certMac) = CertManager.nameAndMac(session.serverCert)
        val devices = _state.value.devices
        // Pairing an already known TV again keeps what the user entered for it.
        val previous = devices.firstOrNull { it.fingerprint == fingerprint } ?: devices.firstOrNull { it.host == host }
        val device = PairedDevice(
            fingerprint = fingerprint,
            name = certName.ifBlank { discovered?.name ?: host },
            host = host,
            port = discovered?.port ?: previous?.port ?: RemoteClient.CONTROL_PORT,
            mac = previous?.mac?.ifEmpty { null } ?: WakeOnLan.normalizeMac(certMac).orEmpty(),
            serviceName = discovered?.name ?: previous?.serviceName.orEmpty(),
        )
        if (previous != null && previous.fingerprint != fingerprint) store.remove(previous.fingerprint)
        store.saveAndSelect(device)
        _state.update { it.copy(repairPrompt = null) }
        // Same TV as before (same address and certificate): the stored data did not change, so reconnect by hand.
        restartLink()
    }

    // ---- Devices and settings ------------------------------------------------------------------

    fun select(device: PairedDevice) {
        scope.launch { store.select(device.fingerprint) }
    }

    fun forget(device: PairedDevice) {
        scope.launch { store.remove(device.fingerprint) }
    }

    /** Stores the Wake-on-LAN MAC address of a TV. Returns false if [mac] is not a MAC address (empty clears it). */
    fun setMac(device: PairedDevice, mac: String): Boolean {
        val normalized = if (mac.isBlank()) "" else WakeOnLan.normalizeMac(mac) ?: return false
        scope.launch { store.update(device.copy(mac = normalized)) }
        return true
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        scope.launch { store.updateSettings(transform) }
    }

    // ---- Messages ------------------------------------------------------------------------------

    fun show(@StringRes text: Int) = _state.update { it.copy(message = UiMessage(text, ++messageCounter)) }

    fun clearMessage(id: Long) = _state.update { if (it.message?.id == id) it.copy(message = null) else it }

    private companion object {
        /** Reconnect backoff: 0.5 s doubling up to 5 s. */
        const val MIN_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 5_000L

        /** After Wake-on-LAN: retry every second for up to 20 s. */
        const val WAKE_RETRY_MS = 1_000L
        const val WAKE_TIMEOUT_MS = 20_000L
        const val WAKE_SETTLE_MS = 2_500L

        /** A single TLS failure can be a TV going to standby; two in a row mean the TV refuses us. */
        const val SSL_FAILURES_BEFORE_PROMPT = 2

        /** Longest voice recording, in case a release of the mic button is missed. */
        const val MAX_VOICE_MS = 15_000L
    }
}
