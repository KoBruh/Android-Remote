package com.androidremote.app.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import kotlin.coroutines.resume

/** A TV found on the local network. */
data class DiscoveredTv(
    /** mDNS service name, usually the TV's name. */
    val name: String,
    val host: String,
    /** Control port (normally 6466). */
    val port: Int,
)

/** Finds Android TVs on the Wi-Fi network via mDNS (`_androidtvremote2._tcp`). */
class Discovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Emits the list of TVs currently visible, for as long as the flow is collected. */
    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredTv>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        fun publish() {
            trySend(synchronized(found) { found.values.toList() })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName) }
                publish()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        // Older Android versions can only resolve one service at a time, so resolve in sequence.
        launch {
            for (info in toResolve) {
                val tv = resolve(info) ?: continue
                synchronized(found) { found[tv.name] = tv }
                publish()
            }
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            // Discovery is a convenience; manual IP entry still works.
        }
        awaitClose {
            toResolve.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION") // resolveService and host: the replacements need API 34.
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredTv? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = serviceInfo.host
                val tv = if (address is Inet4Address) {
                    DiscoveredTv(serviceInfo.serviceName, address.hostAddress.orEmpty(), serviceInfo.port)
                } else {
                    null
                }
                if (continuation.isActive) continuation.resume(tv?.takeIf { it.host.isNotEmpty() })
            }
        }
        try {
            nsd.resolveService(info, listener)
        } catch (_: RuntimeException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
