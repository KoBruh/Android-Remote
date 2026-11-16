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

    /** The Wi-Fi network if it is the active one right now, else null. */
    fun currentWifi(): Network? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return network.takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
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
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }
}
