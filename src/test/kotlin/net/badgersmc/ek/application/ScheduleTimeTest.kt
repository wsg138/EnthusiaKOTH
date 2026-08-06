package net.badgersmc.ek.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedule time parsing — an invalid "HH:mm" value must degrade to "skip"
 * instead of throwing DateTimeException and killing the scheduler tick.
 */
class ScheduleTimeTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone)

    @Test
    fun `valid time parses`() {
        val parsed = ScheduleService.parseScheduleTime("14:30", now, zone)
        assertNotNull(parsed)
        assertEquals(14, parsed!!.hour)
        assertEquals(30, parsed.minute)
    }

    @Test
    fun `midnight is valid`() {
        val parsed = ScheduleService.parseScheduleTime("00:00", now, zone)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.hour)
        assertEquals(0, parsed.minute)
    }

    @Test
    fun `hour out of range is rejected`() {
        assertNull(ScheduleService.parseScheduleTime("25:00", now, zone))
        assertNull(ScheduleService.parseScheduleTime("-1:30", now, zone))
    }

    @Test
    fun `minute out of range is rejected`() {
        assertNull(ScheduleService.parseScheduleTime("12:60", now, zone))
        assertNull(ScheduleService.parseScheduleTime("12:99", now, zone))
    }

    @Test
    fun `non-numeric input is rejected`() {
        assertNull(ScheduleService.parseScheduleTime("abc", now, zone))
        assertNull(ScheduleService.parseScheduleTime("12:xy", now, zone))
        assertNull(ScheduleService.parseScheduleTime("", now, zone))
        assertNull(ScheduleService.parseScheduleTime("12:30:45", now, zone))
    }
}
