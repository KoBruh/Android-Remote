package com.androidremote.app

import android.content.Context
import com.androidremote.app.data.DeviceStore
import com.androidremote.app.data.Settings
import com.androidremote.app.tv.CertManager
import com.androidremote.app.tv.Diagnostics
import com.androidremote.app.tv.Discovery
import com.androidremote.app.tv.NetworkMonitor
import com.androidremote.app.tv.PairingClient
import com.androidremote.app.tv.RemoteClient
import com.androidremote.app.ui.theme.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency injection: builds the app's long-lived objects once per process.
 * Anything that needs the remote takes it from here (see [remoteController]).
 */
class AppContainer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val certManager = CertManager()
    private val networks = NetworkMonitor(context)

    val controller = RemoteController(
        scope = scope,
        store = DeviceStore(context, defaults = Settings(hapticStrength = Tokens.DefaultHapticStrength)),
        client = RemoteClient(certManager),
        pairing = PairingClient(certManager),
        discovery = Discovery(context),
        networks = networks,
        diagnostics = Diagnostics(context, certManager, networks),
    )

    init {
        // Generating the RSA key takes a moment; do it before the first pairing needs it.
        scope.launch(Dispatchers.IO) { runCatching { certManager.clientCertificate() } }
    }
}
