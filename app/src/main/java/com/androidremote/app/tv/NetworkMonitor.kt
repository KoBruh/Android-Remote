package com.androidremote.app.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Tracks the Wi-Fi network. Sockets are bound to it so TV traffic never leaves over mobile
 * data, and a change of network triggers an immediate reconnect.
 */
class NetworkMonitor(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /**
     * The phone's Wi-Fi network, or null if it is not on Wi-Fi.
     *
     * A VPN running over Wi-Fi also reports the Wi-Fi transport, so it is skipped: this is the
     * same network [wifiNetworks] reports, and a reconnect is not triggered for nothing.
     */
    @Suppress("DEPRECATION") // allNetworks: its replacement is the callback in wifiNetworks.
    fun currentWifi(): Network? {
        fun isWifi(network: Network): Boolean {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val active = connectivity.activeNetwork
        if (active != null && isWifi(active)) return active
        return connectivity.allNetworks.lastOrNull(::isWifi)
    }

    /** One line per network the phone is on, for [Diagnostics]: transports (a VPN shows up here), interface and addresses. */
    @Suppress("DEPRECATION") // allNetworks: fine for a one-off report.
    fun describe(): List<String> {
        val active = connectivity.activeNetwork
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
            NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
        )
        return connectivity.allNetworks.map { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            val link = connectivity.getLinkProperties(network)
            val names = transports.filter { capabilities?.hasTransport(it.first) == true }.map { it.second }
            "network $network${if (network == active) " (active)" else ""}: $names ${link?.interfaceName} ${link?.linkAddresses}"
        }.ifEmpty { listOf("no network") }
    }

    /** Emits the Wi-Fi network to use whenever it changes: the newest one available, or null when there is none. */
    fun wifiNetworks(): Flow<Network?> = callbackFlow {
        // During a hand-over the new network appears before the old one is lost, so keep track of all of them.
        val available = LinkedHashSet<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(synchronized(available) { available.apply { add(network) }.lastOrNull() })
            }

            override fun onLost(network: Network) {
                trySend(synchronized(available) { available.apply { remove(network) }.lastOrNull() })
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }
}
