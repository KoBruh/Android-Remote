package com.androidremote.app

import android.app.Application
import android.content.Context
import com.androidremote.app.data.DeviceStore
import com.androidremote.app.tv.CertManager
import com.androidremote.app.tv.Discovery
import com.androidremote.app.tv.NetworkMonitor
import com.androidremote.app.tv.PairingClient
import com.androidremote.app.tv.RemoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency injection: builds the app's long-lived objects once per process.
 * Anything that needs the remote (activity now; tile, widget or service later) takes it from here.
 */
class AppContainer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val certManager = CertManager()

    val controller = RemoteController(
        scope = scope,
        store = DeviceStore(context),
        client = RemoteClient(certManager),
        pairing = PairingClient(certManager),
        discovery = Discovery(context),
        networks = NetworkMonitor(context),
    )

    init {
        // Generating the RSA key takes a moment; do it before the first pairing needs it.
        scope.launch(Dispatchers.IO) { runCatching { certManager.clientCertificate() } }
    }
}

class RemoteApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
