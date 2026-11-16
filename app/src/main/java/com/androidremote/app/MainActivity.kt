package com.androidremote.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import com.androidremote.app.tv.KeyCodes
import com.androidremote.app.ui.devices.DevicePicker
import com.androidremote.app.ui.pairing.PairingDialog
import com.androidremote.app.ui.pairing.PairingTarget
import com.androidremote.app.ui.remote.ButtonAction
import com.androidremote.app.ui.remote.LocalHapticTick
import com.androidremote.app.ui.remote.PressEvent
import com.androidremote.app.ui.remote.RemoteScreen
import com.androidremote.app.ui.settings.SettingsScreen
import com.androidremote.app.ui.theme.AndroidRemoteTheme
import com.androidremote.app.ui.theme.Tokens
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val controller: RemoteController by lazy { (application as RemoteApp).container.controller }
    private var lastVolumeKeyAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge: transparent bars with light icons over the app's own background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        setContent {
            AndroidRemoteTheme {
                // Close (X) closes the app, as in Google's remote.
                AndroidRemoteApp(controller, onClose = ::finishAndRemoveTask)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.setForeground(true)
    }

    override fun onStop() {
        controller.setForeground(false)
        super.onStop()
    }

    /** The phone's volume keys control the TV while the app is open (can be turned off in Settings). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> KeyCodes.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> KeyCodes.VOLUME_DOWN
            else -> null
        }
        val state = controller.state.value
        if (keyCode == null || !state.settings.volumeKeysControlTv || !state.tv.connected) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Holding a key auto-repeats faster than the TV should step; keep it at the on-screen buttons' rate.
            val now = SystemClock.uptimeMillis()
            if (event.repeatCount == 0 || now - lastVolumeKeyAt >= Tokens.RepeatIntervalMs) {
                lastVolumeKeyAt = now
                controller.sendKey(keyCode)
            }
        }
        return true
    }
}

private enum class Screen { Remote, Settings }

/** Root of the UI: switches between the remote and Settings and hosts the dialogs. */
@Composable
fun AndroidRemoteApp(controller: RemoteController, onClose: () -> Unit) {
    val state by controller.state.collectAsState()
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
        if (!granted) controller.micPressed(hasMicPermission = false)
    }

    val hapticTick: () -> Unit = remember(state.settings.hapticsEnabled, state.settings.hapticStrength, view) {
        if (!state.settings.hapticsEnabled) {
            {}
        } else {
            val constant = when (state.settings.hapticStrength) {
                0 -> HapticFeedbackConstants.CLOCK_TICK
                2 -> HapticFeedbackConstants.LONG_PRESS
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            }
            { view.performHapticFeedback(constant) }
        }
    }

    fun onButton(action: ButtonAction, event: PressEvent) {
        when (action) {
            is ButtonAction.Key -> controller.press(action.code, event)
            ButtonAction.Close -> if (event == PressEvent.Tap) onClose()
            ButtonAction.Power -> if (event == PressEvent.Tap) controller.power()
            ButtonAction.Mic -> when (event) {
                PressEvent.Down -> {
                    val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) controller.micPressed(hasMicPermission = true) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                PressEvent.Up -> controller.micReleased()
                else -> Unit
            }
        }
    }

    CompositionLocalProvider(LocalHapticTick provides hapticTick) {
        when (screen) {
            Screen.Remote -> RemoteScreen(
                state = state,
                onButton = ::onButton,
                onDpad = { zone, event -> controller.press(zone.keyCode, event) },
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
            )
        }
    }

    pairing?.let { target ->
        PairingDialog(target = target, controller = controller, onClose = { pairing = null })
    }

    state.repairPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = controller::dismissRepairPrompt,
            containerColor = Tokens.Surface,
            title = { Text(stringResource(R.string.repair_title)) },
            text = {
                Text(
                    stringResource(
                        when (prompt.reason) {
                            RepairReason.CertChanged -> R.string.repair_cert_changed
                            RepairReason.Rejected -> R.string.repair_rejected
                        },
                        prompt.device.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.dismissRepairPrompt()
                    pairing = PairingTarget(host = prompt.device.host)
                }) { Text(stringResource(R.string.settings_repair)) }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissRepairPrompt) { Text(stringResource(R.string.cancel)) }
            },
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
