package com.androidremote.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The last [capacity] connection events with their time: connects, disconnects, power states
 * reported by the TV and keys sent. Shown in Settings in debug builds to find out what the app
 * did around a problem.
 */
class EventLog(
    private val capacity: Int = 300,
    private val clock: () -> Long = System::currentTimeMillis,
    timeZone: TimeZone = TimeZone.getDefault(),
) {
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).apply { this.timeZone = timeZone }
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(line: String) {
        val stamped = synchronized(format) { format.format(Date(clock())) } + " " + line
        _lines.update { (it + stamped).takeLast(capacity) }
    }
}
