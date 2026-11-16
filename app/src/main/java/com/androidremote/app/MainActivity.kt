package com.androidremote.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.androidremote.app.tv.KeyCodes
import com.androidremote.app.ui.AppRoot
import com.androidremote.app.ui.theme.AndroidRemoteTheme
import com.androidremote.app.ui.theme.Tokens

/** The app's only activity: hosts the UI and keeps the TV connection alive while it is visible. */
class MainActivity : ComponentActivity() {

    private val controller: RemoteController by lazy { remoteController }
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
                AppRoot(controller, onClose = ::finishAndRemoveTask)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.acquire()
    }

    override fun onStop() {
        controller.release()
        super.onStop()
    }

    /** The phone's volume keys control the TV while the app is open (can be turned off in Settings). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val tvKey = tvVolumeKey(keyCode) ?: return super.onKeyDown(keyCode, event)
        // Holding a key auto-repeats faster than the TV should step; keep it at the on-screen buttons' rate.
        val now = SystemClock.uptimeMillis()
        if (event.repeatCount == 0 || now - lastVolumeKeyAt >= Tokens.RepeatIntervalMs) {
            lastVolumeKeyAt = now
            controller.sendKey(tvKey)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (tvVolumeKey(keyCode) != null) true else super.onKeyUp(keyCode, event)

    /** The TV key a phone volume key maps to, or null if the phone should handle the key itself. */
    private fun tvVolumeKey(keyCode: Int): Int? {
        val state = controller.state.value
        if (!state.settings.volumeKeysControlTv || !state.tv.connected) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> KeyCodes.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> KeyCodes.VOLUME_DOWN
            else -> null
        }
    }
}
