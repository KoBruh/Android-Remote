package com.androidremote.app

/**
 * Remembers that the TV was put to sleep, so the app stops reconnecting to it.
 *
 * On some TVs (seen on a TCL with networked standby) a new remote connection wakes the screen.
 * The connection drops when the TV goes to standby, and reconnecting straight away would turn
 * the TV back on seconds after the user turned it off.
 */
internal class SleepTracker {

    /** The TV was turned off and should be left alone until the user asks for it again. */
    var asleep = false
        private set

    /** The TV reported its power state over the connection. Null means it has not said. */
    fun onPowerState(isOn: Boolean?) {
        if (isOn != null) asleep = !isOn
    }

    /** POWER was sent to a connected TV whose last reported power state was [wasOn]. */
    fun onPowerKey(wasOn: Boolean?) {
        // Unknown counts as on: the remote was usable, so the press most likely turned it off.
        asleep = wasOn != false
    }

    /** The user wants the TV: the app was opened, another TV was chosen, or a wake-up started. */
    fun onWanted() {
        asleep = false
    }
}
