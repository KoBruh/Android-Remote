package com.androidremote.app.tv

import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds out why a TV cannot be reached. Probes every layer separately (permissions, networks,
 * plain TCP on both ports over both routes, the client key, the TLS handshake) and returns a
 * text report. Shown in debug builds when pairing fails.
 */
class Diagnostics(
    private val context: Context,
    private val certManager: CertManager,
    private val networks: NetworkMonitor,
) {
    /**
     * @param wifi the Wi-Fi network the app binds its sockets to, or null if it uses the default route.
     */
    suspend fun run(host: String, wifi: Network?): String = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        fun line(text: String) {
            report.appendLine(text)
        }

        line("== Device ==")
        line("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        line("App target SDK ${context.applicationInfo.targetSdkVersion}")
        for (permission in PERMISSIONS) {
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            line("${permission.substringAfterLast('.')}: ${if (granted) "granted" else "NOT granted"}")
        }

        line("== Networks ==")
        networks.describe().forEach(::line)
        line("App binds sockets to: ${wifi?.let { "Wi-Fi network $it" } ?: "nothing (default route)"}")

        line("== TCP ==")
        // Both ports over both routes: tells a wrong port from a blocked route (e.g. a VPN).
        val routes = listOf(wifi, null).distinct()
        val reachable = mutableListOf<Network?>()
        for (port in listOf(PairingClient.PAIRING_PORT, RemoteClient.CONTROL_PORT)) {
            for (route in routes) {
                val result = tcpProbe(host, port, route)
                if (port == PairingClient.PAIRING_PORT && result.startsWith("ok")) reachable += route
                line("$host:$port ${routeName(route)}: $result")
            }
        }

        line("== Client key ==")
        certManager.selfTest().forEach(::line)

        line("== TLS on pairing port ${PairingClient.PAIRING_PORT} ==")
        if (reachable.isEmpty()) line("skipped: no TCP connection")
        for (route in reachable) {
            for (tls12Only in listOf(true, false)) {
                line("-- ${routeName(route)}, ${if (tls12Only) "TLS 1.2 only" else "platform default TLS versions"}")
                try {
                    certManager.connect(
                        host, PairingClient.PAIRING_PORT, pinnedFingerprint = null, network = route,
                        trace = ::line, tls12Only = tls12Only,
                    ).close()
                } catch (_: Exception) {
                    // Already in the report through the trace.
                }
            }
        }
        report.toString()
    }

    private fun routeName(route: Network?) = if (route != null) "bound to Wi-Fi" else "default route"

    private fun tcpProbe(host: String, port: Int, route: Network?): String {
        val socket = Socket()
        return try {
            route?.bindSocket(socket)
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            "ok in ${(System.nanoTime() - started) / 1_000_000} ms from ${socket.localAddress.hostAddress}"
        } catch (e: Exception) {
            "FAILED ${e.describe()}"
        } finally {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val TCP_TIMEOUT_MS = 3_000
        val PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_LOCAL_NETWORK",
        )
    }
}
