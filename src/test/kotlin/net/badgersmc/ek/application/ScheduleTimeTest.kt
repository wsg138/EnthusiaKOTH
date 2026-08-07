package net.badgersmc.ek.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScheduleTimeTest {
    @Test
    fun `valid time parses`() {
        val parsed = ScheduleService.parseScheduleTime("14:30")
        assertNotNull(parsed)
        assertEquals(14, parsed!!.hour)
        assertEquals(30, parsed.minute)
    }

    @Test
    fun `midnight is valid`() {
        val parsed = ScheduleService.parseScheduleTime("00:00")
        assertNotNull(parsed)
        assertEquals(0, parsed!!.hour)
        assertEquals(0, parsed.minute)
    }

    @Test
    fun `out of range and malformed values are rejected`() {
        listOf("25:00", "-1:30", "12:60", "12:99", "abc", "12:xy", "", "12:30:45")
            .forEach { assertNull(ScheduleService.parseScheduleTime(it), it) }
    }
}
