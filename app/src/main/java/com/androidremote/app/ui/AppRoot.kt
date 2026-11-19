package com.androidremote.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.androidremote.app.R
import com.androidremote.app.RemoteController
import com.androidremote.app.tv.KeyCodes
import com.androidremote.app.ui.devices.DevicePicker
import com.androidremote.app.ui.pairing.PairingDialog
import com.androidremote.app.ui.pairing.PairingTarget
import com.androidremote.app.ui.pairing.RepairDialog
import com.androidremote.app.ui.remote.ButtonAction
import com.androidremote.app.ui.remote.LocalHapticTick
import com.androidremote.app.ui.remote.PressEvent
import com.androidremote.app.ui.remote.RemoteScreen
import com.androidremote.app.ui.remote.rememberHapticTick
import com.androidremote.app.ui.remote.toKeyDirection
import com.androidremote.app.ui.settings.SettingsScreen
import com.androidremote.app.ui.theme.Tokens
import kotlinx.coroutines.delay

private enum class Screen { Remote, Settings }

/** Root of the UI: switches between the remote and Settings, hosts the dialogs and maps button presses to actions. */
@Composable
fun AppRoot(controller: RemoteController, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
    val log by controller.log.lines.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Remote) }
    var menuOpen by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<PairingTarget?>(null) }
    val context = LocalContext.current
    val view = LocalView.current

    // First run: nothing is paired yet, so open the device menu straight away.
    LaunchedEffect(state.loaded) {
        if (state.loaded && state.devices.isEmpty()) menuOpen = true
    }

    // Status messages disappear on their own.
    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        delay(Tokens.MessageDurationMs)
        controller.clearMessage(message.id)
    }

    DisposableEffect(state.settings.keepScreenOn) {
        view.keepScreenOn = state.settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LocalNetworkPermission(onResult = { granted ->
        if (granted) controller.reconnectNow() else controller.show(R.string.msg_local_network_denied)
    })

    // Hold-to-talk needs the microphone. Without it the Mic button opens voice search on the TV.
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) controller.sendKey(KeyCodes.SEARCH)
    }

    fun onKey(keyCode: Int, event: PressEvent) {
        event.toKeyDirection()?.let { controller.sendKey(keyCode, it) }
    }

    fun onButton(action: ButtonAction, event: PressEvent) {
        when (action) {
            is ButtonAction.Key -> onKey(action.code, event)
            ButtonAction.Close -> if (event == PressEvent.Tap) onClose()
            ButtonAction.Power -> if (event == PressEvent.Tap) controller.power()
            ButtonAction.Mic -> when (event) {
                PressEvent.Down -> {
                    val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) controller.startTalking() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                PressEvent.Up -> controller.stopTalking()
                else -> Unit
            }
        }
    }

    CompositionLocalProvider(LocalHapticTick provides rememberHapticTick(state.settings)) {
        when (screen) {
            Screen.Remote -> RemoteScreen(
                state = state,
                onButton = ::onButton,
                onDpad = { zone, event -> onKey(zone.keyCode, event) },
                onOpenDevices = { menuOpen = true },
                deviceMenu = {
                    DevicePicker(
                        expanded = menuOpen,
                        devices = state.devices,
                        selected = state.selected,
                        discovered = state.discovered,
                        onDismiss = { menuOpen = false },
                        onSelect = {
                            menuOpen = false
                            controller.select(it)
                        },
                        onPair = {
                            menuOpen = false
                            pairing = PairingTarget(host = it.host, discovered = it)
                        },
                        onPairManually = {
                            menuOpen = false
                            pairing = PairingTarget()
                        },
                        onSettings = {
                            menuOpen = false
                            screen = Screen.Settings
                        },
                    )
                },
            )
            Screen.Settings -> SettingsScreen(
                devices = state.devices,
                settings = state.settings,
                onBack = { screen = Screen.Remote },
                onSettingsChange = controller::updateSettings,
                onSetMac = controller::setMac,
                onRepair = { pairing = PairingTarget(host = it.host) },
                onForget = controller::forget,
                log = log,
            )
        }
    }

    pairing?.let { target ->
        PairingDialog(target = target, controller = controller, onClose = { pairing = null })
    }

    state.repairPrompt?.let { prompt ->
        RepairDialog(
            prompt = prompt,
            onRepair = {
                controller.dismissRepairPrompt()
                pairing = PairingTarget(host = prompt.device.host)
            },
            onDismiss = controller::dismissRepairPrompt,
        )
    }
}

/**
 * Android 17+ only lets apps talk to devices on the local network after the user allows it.
 * Asks once when the app opens; older versions need nothing.
 */
@Composable
private fun LocalNetworkPermission(onResult: (Boolean) -> Unit) {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_SDK) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
}

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_PERMISSION_SDK = 37
