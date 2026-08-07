package net.badgersmc.ek.infrastructure.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.ZoneId

class TimezoneParserTest {
    @Test
    fun `valid timezone is returned without warning`() {
        val warnings = mutableListOf<String>()
        assertEquals(ZoneId.of("America/Chicago"), parseZoneId("America/Chicago", warnings::add))
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `invalid timezone uses one guarded fallback`() {
        val warnings = mutableListOf<String>()
        val parsed = parseZoneId("Not/AZone", warnings::add)
        assertSame(DEFAULT_KOTH_ZONE, parsed)
        assertEquals(1, warnings.size)
        assert(warnings.single().contains("Not/AZone"))
    }

    @Test
    fun `blank timezone uses default without an exception`() {
        assertEquals(DEFAULT_KOTH_ZONE, parseZoneId("  ") { error(it) })
    }
}
