package com.androidremote.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A TV this phone has been paired with. */
data class PairedDevice(
    /** SHA-256 fingerprint of the certificate the TV presented while pairing. Identifies the TV. */
    val fingerprint: String,
    val name: String,
    /** IP address (or host name) of the TV. */
    val host: String,
    /** Control port (normally 6466). */
    val port: Int,
    /** MAC address for Wake-on-LAN as "AA:BB:CC:DD:EE:FF", or empty if unknown. */
    val mac: String,
    /** mDNS service name seen at pairing, used to follow the TV when its IP address changes. */
    val serviceName: String,
    /**
     * SHA-256 fingerprint of the certificate the TV must present on the control connection.
     * Empty right after pairing: the first control connection fills it in (trust on first use),
     * and every later connection is rejected unless it matches.
     */
    val pin: String = "",
) {
    companion object {
        /**
         * The device to store after a successful pairing.
         *
         * @param certName name from the TV's certificate, may be blank.
         * @param certMac MAC address from the TV's certificate (already normalised), may be empty.
         * @param discoveredName mDNS name the pairing was started from, if it came from discovery.
         * @param previous the stored entry for this TV if it was paired before. What the user
         *   entered for it (the MAC address) is kept.
         */
        fun fromPairing(
            fingerprint: String,
            certName: String,
            certMac: String,
            host: String,
            defaultPort: Int,
            discoveredName: String?,
            discoveredPort: Int?,
            previous: PairedDevice?,
        ) = PairedDevice(
            fingerprint = fingerprint,
            name = certName.ifBlank { discoveredName ?: previous?.name ?: host },
            host = host,
            port = discoveredPort ?: previous?.port ?: defaultPort,
            mac = previous?.mac?.ifEmpty { null } ?: certMac,
            serviceName = discoveredName ?: previous?.serviceName.orEmpty(),
            // Pairing again always re-pins: the TV may have a new identity (e.g. after a reset).
            pin = "",
        )
    }
}

/** How strong the vibration on a button press is. */
enum class HapticStrength { Light, Medium, Strong }

data class Settings(
    val hapticsEnabled: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.Medium,
    val keepScreenOn: Boolean = false,
    /** The phone's volume keys control the TV while the app is open. */
    val volumeKeysControlTv: Boolean = true,
    /** Debug builds: draw the reference screenshot over the remote. */
    val referenceOverlay: Boolean = false,
    /** The one-time "Networked standby" tip in Settings has been dismissed. */
    val standbyTipDismissed: Boolean = false,
)

data class StoredData(
    val devices: List<PairedDevice> = emptyList(),
    val selectedFingerprint: String? = null,
    val settings: Settings = Settings(),
) {
    /** The TV the remote controls: the selected one, or the first paired one. */
    val selected: PairedDevice?
        get() = devices.firstOrNull { it.fingerprint == selectedFingerprint } ?: devices.firstOrNull()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "android_remote")

/**
 * Persists paired TVs and settings in Jetpack DataStore.
 *
 * @param defaults the settings of a fresh install.
 */
class DeviceStore(context: Context, private val defaults: Settings = Settings()) {

    private val store = context.applicationContext.dataStore

    val data: Flow<StoredData> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toStoredData() }

    /** Adds or replaces a TV (matched by fingerprint) and selects it. */
    suspend fun saveAndSelect(device: PairedDevice) = editDevices(select = device.fingerprint) { devices ->
        devices.filterNot { it.fingerprint == device.fingerprint } + device
    }

    /** Replaces a TV's stored details without changing the selection. */
    suspend fun update(device: PairedDevice) = editDevices { devices ->
        devices.map { if (it.fingerprint == device.fingerprint) device else it }
    }

    suspend fun remove(fingerprint: String) = editDevices { devices ->
        devices.filterNot { it.fingerprint == fingerprint }
    }

    suspend fun select(fingerprint: String) {
        store.edit { it[SELECTED] = fingerprint }
    }

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val settings = transform(prefs.toSettings())
            prefs[HAPTICS_ENABLED] = settings.hapticsEnabled
            prefs[HAPTIC_STRENGTH] = settings.hapticStrength.ordinal
            prefs[KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[VOLUME_KEYS] = settings.volumeKeysControlTv
            prefs[REFERENCE_OVERLAY] = settings.referenceOverlay
            prefs[STANDBY_TIP_DISMISSED] = settings.standbyTipDismissed
        }
    }

    private suspend fun editDevices(select: String? = null, transform: (List<PairedDevice>) -> List<PairedDevice>) {
        store.edit { prefs ->
            prefs[DEVICES] = encodeDevices(transform(decodeDevices(prefs[DEVICES])))
            if (select != null) prefs[SELECTED] = select
        }
    }

    private fun Preferences.toStoredData() = StoredData(
        devices = decodeDevices(this[DEVICES]),
        selectedFingerprint = this[SELECTED],
        settings = toSettings(),
    )

    private fun Preferences.toSettings() = Settings(
        hapticsEnabled = this[HAPTICS_ENABLED] ?: defaults.hapticsEnabled,
        hapticStrength = this[HAPTIC_STRENGTH]?.let(HapticStrength.entries::getOrNull) ?: defaults.hapticStrength,
        keepScreenOn = this[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
        volumeKeysControlTv = this[VOLUME_KEYS] ?: defaults.volumeKeysControlTv,
        referenceOverlay = this[REFERENCE_OVERLAY] ?: defaults.referenceOverlay,
        standbyTipDismissed = this[STANDBY_TIP_DISMISSED] ?: defaults.standbyTipDismissed,
    )

    private fun encodeDevices(devices: List<PairedDevice>): String = JSONArray().apply {
        for (device in devices) {
            put(
                JSONObject()
                    .put("fingerprint", device.fingerprint)
                    .put("name", device.name)
                    .put("host", device.host)
                    .put("port", device.port)
                    .put("mac", device.mac)
                    .put("serviceName", device.serviceName)
                    .put("pin", device.pin),
            )
        }
    }.toString()

    private fun decodeDevices(json: String?): List<PairedDevice> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                PairedDevice(
                    fingerprint = o.getString("fingerprint"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    mac = o.optString("mac"),
                    serviceName = o.optString("serviceName"),
                    pin = o.optString("pin"),
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private companion object {
        val DEVICES = stringPreferencesKey("devices")
        val SELECTED = stringPreferencesKey("selected")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        val REFERENCE_OVERLAY = booleanPreferencesKey("reference_overlay")
        val STANDBY_TIP_DISMISSED = booleanPreferencesKey("standby_tip_dismissed")
    }
}
