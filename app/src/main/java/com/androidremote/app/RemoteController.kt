package com.androidremote.app

import android.net.Network
import androidx.annotation.StringRes
import com.androidremote.app.data.DeviceStore
import com.androidremote.app.data.PairedDevice
import com.androidremote.app.data.Settings
import com.androidremote.app.data.StoredData
import com.androidremote.app.tv.CertManager
import com.androidremote.app.tv.CertSubject
import com.androidremote.app.tv.Diagnostics
import com.androidremote.app.tv.DiscoveredTv
import com.androidremote.app.tv.Discovery
import com.androidremote.app.tv.KeyCodes
import com.androidremote.app.tv.KeyDirection
import com.androidremote.app.tv.NetworkMonitor
import com.androidremote.app.tv.PairingClient
import com.androidremote.app.tv.PairingSession
import com.androidremote.app.tv.RemoteClient
import com.androidremote.app.tv.Trace
import com.androidremote.app.tv.TvState
import com.androidremote.app.tv.VoiceRecorder
import com.androidremote.app.tv.WakeOnLan
import com.androidremote.app.tv.isCertMismatch
import com.androidremote.app.tv.logcatTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

enum class LinkState { Disconnected, Connecting, Connected }

/** Why the app suggests pairing again. */
enum class RepairReason {
    /** The TV presents a different certificate than before (e.g. after a factory reset). */
    CertChanged,

    /** The TV refuses this phone's certificate (e.g. its remote service data was cleared). */
    Rejected,
}

data class RepairPrompt(val device: PairedDevice, val reason: RepairReason)

/** How a power press ended. [message] is what to tell the user, if anything. */
enum class PowerResult(@StringRes val message: Int?) {
    /** The TV was reachable and got a POWER key. */
    Toggled(null),

    /** The TV was woken with Wake-on-LAN and is on. */
    Woke(null),

    /** No TV is paired. */
    NoTv(R.string.msg_no_tv),

    /** The TV is not reachable and has no MAC address to wake it with. */
    NoMac(R.string.msg_no_mac),

    /** Wake-on-LAN was sent but the TV did not come back. */
    WakeFailed(R.string.msg_wake_failed),
}

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
 * The app's single source of truth: it owns the connection to the selected TV, the power/wake
 * logic and voice, and exposes one [state] flow.
 *
 * It lives for the whole process (see [AppContainer]) and knows nothing about the UI, so the
 * activity, a Quick Settings tile, a widget or a notification can all drive the same instance:
 * - whoever needs the TV connection kept alive calls [acquire], and [release] when done;
 * - one-shot callers just call a suspend function such as [togglePowerOrWake], which holds
 *   the connection for as long as it runs.
 *
 * All functions must be called on the main thread.
 */
class RemoteController(
    private val scope: CoroutineScope,
    private val store: DeviceStore,
    private val client: RemoteClient,
    private val pairing: PairingClient,
    private val discovery: Discovery,
    private val networks: NetworkMonitor,
    private val diagnostics: Diagnostics,
) {
    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    /** How many callers currently want the TV connection kept alive. */
    private var holders = 0
    private var network: Network? = null
    private var allDiscovered: List<DiscoveredTv> = emptyList()

    private var linkJob: Job? = null
    private var watchJob: Job? = null
    private var powerJob: Job? = null
    private var voiceJob: Job? = null

    /** A wake-up is running: retry every second instead of backing off. */
    private var waking = false

    /** The TV accepted keys at some point during the current connection attempt. */
    private var connectedThisAttempt = false

    /** The TLS handshake succeeded during the current connection attempt. */
    @Volatile
    private var securedThisAttempt = false

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

    // ---- Keeping the connection alive ----------------------------------------------------------

    /** Keeps the TV connection alive until the matching [release]. The activity calls this while it is visible. */
    fun acquire() {
        if (holders++ > 0) return
        network = networks.currentWifi()
        watchJob = scope.launch {
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
        restartLink()
    }

    fun release() {
        if (holders == 0 || --holders > 0) return
        watchJob?.cancel()
        micHeld = false
        allDiscovered = emptyList()
        _state.update { it.copy(discovered = emptyList()) }
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
        if (holders == 0 || device == null) {
            _state.update { it.copy(busy = false) }
            return
        }
        linkJob = scope.launch { runLink(device.fingerprint) }
    }

    /** Keeps connecting to the TV with [fingerprint] until cancelled, or until pairing again is the only way forward. */
    private suspend fun runLink(fingerprint: String) {
        var backoffMs = MIN_BACKOFF_MS
        var tlsFailures = 0
        _state.update { it.copy(busy = true) }
        while (true) {
            // Read the stored entry afresh each time: its address or pin may have been updated meanwhile.
            val device = _state.value.devices.firstOrNull { it.fingerprint == fingerprint } ?: return
            connectedThisAttempt = false
            securedThisAttempt = false
            try {
                client.run(device.host, device.port, device.pin.ifEmpty { null }, network) { presented ->
                    securedThisAttempt = true
                    // First control connection after pairing: pin the certificate the TV presented.
                    if (device.pin.isEmpty()) scope.launch { store.update(device.copy(pin = presented)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else (unreachable TV, dropped connection, missing network permission) is retried.
                coroutineContext.ensureActive()
                if (connectedThisAttempt) backoffMs = MIN_BACKOFF_MS
                when {
                    securedThisAttempt -> tlsFailures = 0
                    e.isCertMismatch() -> return stopForRepair(device, RepairReason.CertChanged)
                    // A TV that is starting up or going to standby can also break the handshake, so
                    // only conclude that it refuses us after repeated failures outside a wake-up.
                    e is SSLException && !waking && ++tlsFailures >= TLS_FAILURES_BEFORE_PROMPT ->
                        return stopForRepair(device, RepairReason.Rejected)
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

    /** Waits until the TV accepts keys. Returns false if that does not happen within [timeoutMs]. */
    private suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { client.state.first { it.connected } } != null

    // ---- Keys ----------------------------------------------------------------------------------

    /** Sends a key to the TV. Returns false if there is no connection. */
    fun sendKey(keyCode: Int, direction: KeyDirection = KeyDirection.SHORT): Boolean = client.sendKey(keyCode, direction)

    // ---- Power and wake ------------------------------------------------------------------------

    /** Power button of the remote: runs [togglePowerOrWake] and shows its outcome as a status message. */
    fun power() {
        if (powerJob?.isActive == true) return
        powerJob = scope.launch { togglePowerOrWake().message?.let(::show) }
    }

    /**
     * Toggles power if the TV is reachable. If it is not, wakes it with Wake-on-LAN, reconnects,
     * and only sends POWER if the TV still reports that it is off.
     *
     * Holds the TV connection for as long as it runs, so it also works when nothing else does
     * (e.g. from a Quick Settings tile).
     */
    suspend fun togglePowerOrWake(): PowerResult {
        acquire()
        try {
            val device = _state.first { it.loaded }.selected ?: return PowerResult.NoTv
            // Already connected, or still on the first connection attempt (e.g. the app was just
            // opened): the TV is most likely reachable, so this is a normal power press.
            if (_state.value.tv.connected || (_state.value.busy && awaitConnected(CONNECT_GRACE_MS))) {
                client.sendKey(KeyCodes.POWER)
                return PowerResult.Toggled
            }
            val mac = WakeOnLan.parseMac(device.mac)
            if (mac == null) {
                restartLink()
                return PowerResult.NoMac
            }
            waking = true
            try {
                val wifi = network
                WakeOnLan.send(mac) { socket -> wifi?.bindSocket(socket) }
                restartLink()
                if (!awaitConnected(WAKE_TIMEOUT_MS)) return PowerResult.WakeFailed
                // The TV may report "off" for a moment while it wakes; give it time to settle so
                // POWER is not sent to a TV that is already coming up (that would turn it off again).
                withTimeoutOrNull(WAKE_SETTLE_MS) { client.state.first { !it.connected || it.isOn == true } }
                val tv = client.state.value
                if (tv.connected && tv.isOn == false) client.sendKey(KeyCodes.POWER)
                return PowerResult.Woke
            } finally {
                waking = false
                _state.update { it.copy(busy = false) }
            }
        } finally {
            release()
        }
    }

    // ---- Voice ---------------------------------------------------------------------------------

    /**
     * Starts streaming the phone microphone to the TV until [stopTalking]. Needs the RECORD_AUDIO
     * permission; without it, send [KeyCodes.SEARCH] instead to open the TV's own voice search.
     */
    fun startTalking() {
        if (!_state.value.tv.connected || voiceJob?.isActive == true) return
        micHeld = true
        voiceJob = scope.launch {
            val recorder = withContext(Dispatchers.IO) { VoiceRecorder.open() }
            if (recorder == null) {
                client.sendKey(KeyCodes.SEARCH)
                return@launch
            }
            try {
                _state.update { it.copy(micActive = true) }
                client.talk(recorder, MAX_VOICE_MS) { micHeld }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { recorder.close() }
                _state.update { it.copy(micActive = false) }
            }
        }
    }

    fun stopTalking() {
        micHeld = false
    }

    // ---- Pairing -------------------------------------------------------------------------------

    /**
     * Starts pairing with the TV at [host]: the TV shows its code.
     *
     * @param trace told about every stage of the attempt (see [PairingClient.start]).
     * @throws java.io.IOException if the TV cannot be reached.
     */
    suspend fun startPairing(host: String, trace: Trace = {}): PairingSession =
        pairing.start(host, network ?: networks.currentWifi(), logcatTrace(trace))

    /** Probes every layer between this phone and the TV at [host] and returns a text report (debug builds). */
    suspend fun diagnose(host: String): String = diagnostics.run(host, network ?: networks.currentWifi())

    /**
     * Finishes pairing with the code from the TV's screen, then saves and selects the TV.
     *
     * @param discovered the discovery entry the pairing was started from, if any.
     * @throws com.androidremote.app.tv.InvalidPairingCodeException if the code is wrong (try again).
     * @throws java.io.IOException if the TV rejected the code or closed the connection.
     */
    suspend fun finishPairing(session: PairingSession, code: String, host: String, discovered: DiscoveredTv?) {
        session.finish(code)
        session.close()
        val fingerprint = CertManager.fingerprint(session.serverCert)
        val (certName, certMac) = CertSubject.nameAndMac(session.serverCert)
        val devices = _state.value.devices
        val previous = devices.firstOrNull { it.fingerprint == fingerprint } ?: devices.firstOrNull { it.host == host }
        val device = PairedDevice.fromPairing(
            fingerprint = fingerprint,
            certName = certName,
            certMac = WakeOnLan.normalizeMac(certMac).orEmpty(),
            host = host,
            defaultPort = RemoteClient.CONTROL_PORT,
            discoveredName = discovered?.name,
            discoveredPort = discovered?.port,
            previous = previous,
        )
        if (previous != null && previous.fingerprint != fingerprint) store.remove(previous.fingerprint)
        store.saveAndSelect(device)
        // Connect with the new entry, not with whatever was stored for this TV before.
        _state.first { device in it.devices }
        _state.update { it.copy(repairPrompt = null) }
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

        /** How long a power press waits for a connection attempt that is already running. */
        const val CONNECT_GRACE_MS = 1_500L

        /** After Wake-on-LAN: retry every second for up to 20 s. */
        const val WAKE_RETRY_MS = 1_000L
        const val WAKE_TIMEOUT_MS = 20_000L

        /** How long a freshly woken TV gets to report that it is on before POWER is sent. */
        const val WAKE_SETTLE_MS = 4_000L

        /** A single TLS failure can be a TV going to standby; several in a row mean the TV refuses us. */
        const val TLS_FAILURES_BEFORE_PROMPT = 2

        /** Longest voice recording, in case a release of the mic button is missed. */
        const val MAX_VOICE_MS = 15_000L
    }
}
