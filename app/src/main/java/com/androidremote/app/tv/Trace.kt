package com.androidremote.app.tv

import android.util.Log

/** Receives one line per stage of a connection attempt, for Logcat and the debug report. */
typealias Trace = (String) -> Unit

private const val LOG_TAG = "AndroidRemote"

/** A [Trace] that writes every line to Logcat (tag "AndroidRemote") and then passes it on. */
fun logcatTrace(next: Trace = {}): Trace = { line ->
    Log.d(LOG_TAG, line)
    next(line)
}

/** Class and message of this exception and of every cause behind it. */
fun Throwable.describe(): String =
    generateSequence(this) { it.cause }.take(6).joinToString("\n  caused by ") { "${it.javaClass.name}: ${it.message}" }
