package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.ScheduleConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.persistence.InMemoryScheduleStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ScheduleServiceTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun `on-time occurrence starts exactly once`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(now.minusSeconds(1)) }
        val koth = mockKoth()
        service(now, listOf(arena("capture", listOf("12:00"))), store, koth).tickAt(now)
        verify(exactly = 1) { koth.queueStart(match { it.id == "capture" }, EventKind.SCHEDULED, now) }
    }

    @Test
    fun `tick delayed by thirty-one seconds still starts occurrence`() {
        val now = Instant.parse("2026-08-06T12:00:31Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-06T11:59:50Z")) }
        val koth = mockKoth()
        service(now, listOf(arena("capture", listOf("12:00"))), store, koth).tickAt(now)
        verify(exactly = 1) { koth.queueStart(any(), EventKind.SCHEDULED, Instant.parse("2026-08-06T12:00:00Z")) }
    }

    @Test
    fun `tick delayed several minutes still starts within catch-up window`() {
        val now = Instant.parse("2026-08-06T12:05:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-06T11:59:00Z")) }
        val koth = mockKoth()
        service(now, listOf(arena("capture", listOf("12:00"))), store, koth).tickAt(now)
        verify(exactly = 1) { koth.queueStart(any(), EventKind.SCHEDULED, Instant.parse("2026-08-06T12:00:00Z")) }
    }

    @Test
    fun `duplicate tick and restarted service do not duplicate occurrence`() {
        val now = Instant.parse("2026-08-06T12:00:05Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-06T11:59:55Z")) }
        val arenas = listOf(arena("capture", listOf("12:00")))
        val firstKoth = mockKoth()
        service(now, arenas, store, firstKoth).apply {
            tickAt(now)
            tickAt(now)
            reload()
            tickAt(now)
        }
        verify(exactly = 1) { firstKoth.queueStart(any(), EventKind.SCHEDULED, any()) }

        val restartedKoth = mockKoth()
        service(now, arenas, store, restartedKoth).tickAt(now.plusSeconds(1))
        verify(exactly = 0) { restartedKoth.queueStart(any(), any(), any()) }
    }

    @Test
    fun `duplicate configured times keep distinct arena identity`() {
        val now = Instant.parse("2026-08-06T11:00:00Z")
        val service = service(
            now,
            listOf(arena("alpha", listOf("12:00")), arena("beta", listOf("12:00"))),
            InMemoryScheduleStateStore(),
            mockKoth(),
        )
        val occurrences = service.occurrencesBetween(now, Instant.parse("2026-08-06T12:00:00Z"))
            .filter { it.instant == Instant.parse("2026-08-06T12:00:00Z") }
        assertEquals(listOf("alpha", "beta"), occurrences.map { it.arenaId }.sorted())
        assertEquals(2, occurrences.map { it.id }.distinct().size)
    }

    @Test
    fun `midnight rollover starts the new-day occurrence`() {
        val now = Instant.parse("2026-08-07T00:00:31Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-06T23:59:50Z")) }
        val koth = mockKoth()
        service(now, listOf(arena("midnight", listOf("00:00"))), store, koth).tickAt(now)
        verify(exactly = 1) { koth.queueStart(match { it.id == "midnight" }, EventKind.SCHEDULED, Instant.parse("2026-08-07T00:00:00Z")) }
    }

    @Test
    fun `disabled schedule advertises and starts nothing`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(now.minusSeconds(30)) }
        val koth = mockKoth()
        val service = service(now, listOf(arena("capture", listOf("12:00"))), store, koth, enabled = false)
        service.tickAt(now)
        assertNull(service.nextScheduledStart())
        assertNull(service.nextEventInfo())
        verify(exactly = 0) { koth.queueStart(any(), any(), any()) }
    }

    @Test
    fun `invalid time is logged once and ignored`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val logs = mutableListOf<String>()
        val service = service(now, listOf(arena("capture", listOf("25:99"))), InMemoryScheduleStateStore(), mockKoth(), logs = logs)
        service.occurrencesBetween(now, now.plusSeconds(3600))
        service.occurrencesBetween(now, now.plusSeconds(3600))
        assertEquals(1, logs.count { it.contains("25:99") })
    }

    @Test
    fun `pre-start warning is actually delivered once`() {
        val now = Instant.parse("2026-08-06T11:55:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(now.minusSeconds(1)) }
        val warnings = mutableListOf<Pair<String, Int>>()
        val service = service(now, listOf(arena("capture", listOf("12:00"))), store, mockKoth(), warnings = warnings)
        service.tickAt(now)
        service.tickAt(now.plusSeconds(1))
        assertEquals(listOf("capture" to 5), warnings)
    }

    @Test
    fun `server offline across stale occurrences does not replay old days`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-03T00:00:00Z")) }
        val koth = mockKoth()
        service(now, listOf(arena("morning", listOf("08:00"))), store, koth).tickAt(now)
        verify(exactly = 0) { koth.queueStart(any(), any(), any()) }
    }

    @Test
    fun `next event uses the exact occurrence arena for duplicate times`() {
        val now = Instant.parse("2026-08-06T11:00:00Z")
        val service = service(
            now,
            listOf(arena("alpha", listOf("12:00")), arena("beta", listOf("12:00"))),
            InMemoryScheduleStateStore(),
            mockKoth(),
        )
        val next = service.nextEventInfo()
        assertTrue(next?.first in setOf("alpha", "beta"))
        assertEquals("60m 0s", next?.second)
    }

    private fun mockKoth(): KothService = mockk(relaxed = true) {
        every { queueStart(any(), any(), any()) } returns true
    }

    private fun service(
        now: Instant,
        arenaList: List<KothArena>,
        store: InMemoryScheduleStateStore,
        koth: KothService,
        enabled: Boolean = true,
        warnings: MutableList<Pair<String, Int>> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
    ) = ScheduleService(
        cfgLoader = {
            EnthusiaKothConfig(
                schedule = ScheduleConfig(
                    enabled = enabled,
                    zone = zone,
                    preStartWarningSeconds = 300,
                    times = emptyList(),
                ),
            )
        },
        kothService = koth,
        arenas = { arenaList.associateBy { it.id } },
        stateStore = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        warningSink = { id, minutes -> warnings += id to minutes },
        logger = logs::add,
    )

    private fun arena(id: String, schedule: List<String>) = KothArena(
        id = id,
        family = "capture",
        zone = mockk<CaptureZone>(relaxed = true),
        durationSeconds = 60,
        captureSeconds = 10,
        schedule = schedule,
    )
}
