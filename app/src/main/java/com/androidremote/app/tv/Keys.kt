package com.androidremote.app.tv

import com.androidremote.app.proto.RemoteDirection

/** Android KeyEvent codes sent to the TV. Any other KeyEvent code works too. */
object KeyCodes {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val SEARCH = 84
    const val VOLUME_MUTE = 164
}

/** How a key is pressed: a tap, or the start and end of a hold. */
enum class KeyDirection(internal val proto: RemoteDirection) {
    SHORT(RemoteDirection.SHORT),
    START_LONG(RemoteDirection.START_LONG),
    END_LONG(RemoteDirection.END_LONG),
}
