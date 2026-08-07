package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.ScheduleConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.persistence.FileOperationalStateStore
import net.badgersmc.ek.infrastructure.persistence.InMemoryScheduleStateStore
import net.badgersmc.ek.infrastructure.persistence.ScheduleClaimStatus
import net.badgersmc.ek.infrastructure.persistence.ScheduleStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ScheduleServiceTest {
    private val zone = ZoneId.of("UTC")

    @TempDir
    lateinit var temp: Path

    @Test
    fun `on-time occurrence starts exactly once`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(now.minusSeconds(1)) }
        val koth = mockKoth()
        service(now, listOf(arena("capture", listOf("12:00"))), store, koth).tickAt(now)
        verify(exactly = 1) { koth.queueStart(match { it.id == "capture" }, EventKind.SCHEDULED, now, any(), any()) }
    }

    @Test
    fun `tick delayed by thirty-one seconds still starts occurrence`() {
        val now = Instant.parse("2026-08-06T12:00:31Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-06T11:59:50Z")) }
        val koth = mockKoth()
        service(now, listOf(arena("capture", listOf("12:00"))), store, koth).tickAt(now)
        verify(exactly = 1) {
            koth.queueStart(any(), EventKind.SCHEDULED, Instant.parse("2026-08-06T12:00:00Z"), any(), any())
        }
    }

    @Test
    fun `tick delayed several minutes still starts within catch-up window`() {
        val now = Instant.parse("2026-08-06T12:05:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-06T11:59:00Z")) }
        val koth = mockKoth()
        service(now, listOf(arena("capture", listOf("12:00"))), store, koth).tickAt(now)
        verify(exactly = 1) {
            koth.queueStart(any(), EventKind.SCHEDULED, Instant.parse("2026-08-06T12:00:00Z"), any(), any())
        }
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
        verify(exactly = 1) { firstKoth.queueStart(any(), EventKind.SCHEDULED, any(), any(), any()) }

        val restartedKoth = mockKoth()
        service(now, arenas, store, restartedKoth).tickAt(now.plusSeconds(1))
        verify(exactly = 0) { restartedKoth.queueStart(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failed enqueue and failed claim release recover after restart exactly once`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val scheduleFile = temp.resolve("schedule-recovery.dat").toFile()
        val queueFile = temp.resolve("queue-recovery.dat").toFile()
        var scheduleWrites = 0
        val failingStore = FileOperationalStateStore(
            scheduleFile = scheduleFile,
            queueFile = queueFile,
            logger = { _, _ -> },
            schedulePersistInterceptor = {
                scheduleWrites++
                if (scheduleWrites == 2) error("injected claim release failure")
            },
        )
        val firstKoth = mockk<KothService>(relaxed = true) {
            every { queueStart(any(), any(), any(), any(), any()) } returns false
        }

        service(now, listOf(arena("capture", listOf("12:00"))), failingStore, firstKoth).tickAt(now)

        val pendingClaim = failingStore.pendingClaims("start:").keys.single()
        assertEquals(ScheduleClaimStatus.PENDING, failingStore.claimStatus(pendingClaim))
        verify(exactly = 1) { firstKoth.queueStart(any(), EventKind.SCHEDULED, now, any(), any()) }

        val restartedStore = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> })
        val restartedKoth = mockKoth()
        val restarted = service(now.plusSeconds(1), listOf(arena("capture", listOf("12:00"))), restartedStore, restartedKoth)
        restarted.tickAt(now.plusSeconds(1))
        restarted.tickAt(now.plusSeconds(2))

        verify(exactly = 1) { restartedKoth.queueStart(any(), EventKind.SCHEDULED, now, any(), any()) }
        assertEquals(ScheduleClaimStatus.COMMITTED, restartedStore.claimStatus(pendingClaim))
    }

    @Test
    fun `pending occurrence recovery survives schedule config change`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val scheduleFile = temp.resolve("schedule-config-change.dat").toFile()
        val queueFile = temp.resolve("queue-config-change.dat").toFile()
        var scheduleWrites = 0
        val failingStore = FileOperationalStateStore(
            scheduleFile = scheduleFile,
            queueFile = queueFile,
            logger = { _, _ -> },
            schedulePersistInterceptor = {
                scheduleWrites++
                if (scheduleWrites == 2) error("injected claim release failure")
            },
        )
        val firstKoth = mockk<KothService>(relaxed = true) {
            every { queueStart(any(), any(), any(), any(), any()) } returns false
        }
        val originalArena = arena("capture", listOf("12:00"))

        service(now, listOf(originalArena), failingStore, firstKoth).tickAt(now)
        val pendingClaim = failingStore.pendingClaims("start:").entries.single()
        assertEquals("capture", pendingClaim.value.arenaId)
        assertEquals(now, pendingClaim.value.occurrence)

        val restartedStore = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> })
        val restartedKoth = mockKoth()
        val changedArena = arena("capture", listOf("13:00"))
        val restarted = service(now.plusSeconds(1), listOf(changedArena), restartedStore, restartedKoth)

        restarted.tickAt(now.plusSeconds(1))
        restarted.tickAt(now.plusSeconds(2))

        verify(exactly = 1) {
            restartedKoth.queueStart(
                match { it.id == "capture" },
                EventKind.SCHEDULED,
                now,
                pendingClaim.value.teamMode ?: TeamMode.SOLO,
                pendingClaim.key.removePrefix("start:"),
            )
        }
        assertEquals(ScheduleClaimStatus.COMMITTED, restartedStore.claimStatus(pendingClaim.key))
    }

    @Test
    fun `stale recovered pending occurrence survives queue failure and retention pruning`() {
        val occurrence = Instant.parse("2026-08-01T12:00:00Z")
        val restartNow = occurrence.plusSeconds(4 * 24 * 60 * 60L)
        val claim = "start:durable-old-occurrence"
        val store = InMemoryScheduleStateStore()
        assertTrue(store.claim(claim, occurrence, "capture", TeamMode.GUILD))
        var attempts = 0
        val koth = mockk<KothService>(relaxed = true) {
            every { queueStart(any(), any(), any(), any(), any()) } answers { ++attempts >= 2 }
            every { queuedOccurrenceIds() } answers {
                if (attempts >= 2) setOf("durable-old-occurrence") else emptySet()
            }
        }
        val scheduler = service(
            restartNow,
            listOf(arena("capture", emptyList())),
            store,
            koth,
        )

        scheduler.tickAt(restartNow)
        assertEquals(ScheduleClaimStatus.PENDING, store.claimStatus(claim))

        scheduler.tickAt(restartNow.plusSeconds(1))

        assertEquals(2, attempts)
        assertEquals(ScheduleClaimStatus.COMMITTED, store.claimStatus(claim))
        verify(exactly = 2) {
            koth.queueStart(
                match { it.id == "capture" },
                EventKind.SCHEDULED,
                occurrence,
                TeamMode.GUILD,
                "durable-old-occurrence",
            )
        }
    }

    @Test
    fun `queue rejection with successful release rewinds cursor so occurrence retries`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val store = InMemoryScheduleStateStore()
        var attempts = 0
        val koth = mockk<KothService>(relaxed = true) {
            every { queueStart(any(), any(), any(), any(), any()) } answers { ++attempts > 1 }
        }
        val scheduler = service(now, listOf(arena("capture", listOf("12:00"))), store, koth)

        scheduler.tickAt(now)
        scheduler.tickAt(now.plusSeconds(1))

        assertEquals(2, attempts)
        verify(exactly = 2) { koth.queueStart(any(), EventKind.SCHEDULED, now, any(), any()) }
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
    fun `resolved legacy schedule rotates global times instead of multiplying them by arenas`() {
        val now = Instant.parse("2026-08-06T01:00:00Z")
        val service = service(
            now,
            listOf(arena("alpha", emptyList()), arena("beta", emptyList())),
            InMemoryScheduleStateStore(),
            mockKoth(),
            globalTimes = listOf("08:00", "12:00", "16:00"),
        )

        val first = service.occurrencesForDate(now.atZone(zone).toLocalDate())
        val second = service.occurrencesForDate(now.atZone(zone).toLocalDate())

        assertEquals(3, first.size)
        assertEquals(listOf(1, 2), first.groupingBy { it.arenaId }.eachCount().values.sorted())
        assertEquals(first, second)
        assertEquals(
            listOf("08:00", "12:00", "16:00"),
            first.map { it.instant.atZone(zone).toLocalTime().toString() },
        )
    }

    @Test
    fun `midnight rollover starts the new-day occurrence`() {
        val now = Instant.parse("2026-08-07T00:00:31Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-06T23:59:50Z")) }
        val koth = mockKoth()
        service(now, listOf(arena("midnight", listOf("00:00"))), store, koth).tickAt(now)
        verify(exactly = 1) {
            koth.queueStart(match { it.id == "midnight" }, EventKind.SCHEDULED, Instant.parse("2026-08-07T00:00:00Z"), any(), any())
        }
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
        verify(exactly = 0) { koth.queueStart(any(), any(), any(), any(), any()) }
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
    fun `discord pre-start warning is independent and delivered once`() {
        val now = Instant.parse("2026-08-06T11:50:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(now.minusSeconds(1)) }
        val discordWarnings = mutableListOf<Pair<String, Int>>()
        val service = service(
            now,
            listOf(arena("capture", listOf("12:00"))),
            store,
            mockKoth(),
            discordMinutes = 10,
            discordWarnings = discordWarnings,
        )
        service.tickAt(now)
        service.tickAt(now.plusSeconds(1))
        assertEquals(listOf("capture" to 10), discordWarnings)
    }

    @Test
    fun `server offline across stale occurrences does not replay old days`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-08-03T00:00:00Z")) }
        val koth = mockKoth()
        service(now, listOf(arena("morning", listOf("08:00"))), store, koth).tickAt(now)
        verify(exactly = 0) { koth.queueStart(any(), any(), any(), any(), any()) }
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
        every { queueStart(any(), any(), any(), any(), any()) } returns true
    }

    private fun service(
        now: Instant,
        arenaList: List<KothArena>,
        store: ScheduleStateStore,
        koth: KothService,
        enabled: Boolean = true,
        warnings: MutableList<Pair<String, Int>> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
        discordMinutes: Int = 0,
        discordWarnings: MutableList<Pair<String, Int>> = mutableListOf(),
        globalTimes: List<String> = emptyList(),
    ) = ScheduleService(
        cfgLoader = {
            EnthusiaKothConfig(
                schedule = ScheduleConfig(
                    enabled = enabled,
                    zone = zone,
                    preStartWarningSeconds = 300,
                    times = globalTimes,
                ),
            )
        },
        kothService = koth,
        arenas = { arenaList.associateBy { it.id } },
        stateStore = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        warningSink = { id, minutes -> warnings += id to minutes },
        logger = logs::add,
        discordWarningMinutes = { discordMinutes },
        discordWarningSink = { id, minutes -> discordWarnings += id to minutes },
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
