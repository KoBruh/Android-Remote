package com.androidremote.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class EventLogTest {

    private var now = 0L
    private val log = EventLog(capacity = 3, clock = { now }, timeZone = TimeZone.getTimeZone("UTC"))

    @Test
    fun `lines are stamped with the time of day`() {
        now = ((13 * 60 + 5) * 60 + 9) * 1000L + 42
        log.add("Connected")
        assertEquals(listOf("13:05:09.042 Connected"), log.lines.value)
    }

    @Test
    fun `only the newest lines are kept`() {
        for (i in 1..5) log.add("line $i")
        assertEquals(listOf("line 3", "line 4", "line 5"), log.lines.value.map { it.substringAfter(' ') })
    }
}
