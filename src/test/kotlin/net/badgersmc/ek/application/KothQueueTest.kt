package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.badgersmc.ek.config.DiscordConfig
import net.badgersmc.ek.config.DisplayConfig
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.LockConfig
import net.badgersmc.ek.config.PrivateTestingConfig
import net.badgersmc.ek.config.ScheduleConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.domain.PrivateTestAccess
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.EventQueueStore
import net.badgersmc.ek.infrastructure.persistence.FileOperationalStateStore
import net.badgersmc.ek.infrastructure.persistence.InMemoryEventQueueStore
import net.badgersmc.ek.infrastructure.persistence.ScheduleClaimStatus
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class KothQueueTest {
    @TempDir
    lateinit var temp: Path

    private val now = Instant.parse("2026-08-06T12:00:00Z")

    @BeforeEach
    fun mockBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns mutableListOf()
        every { Bukkit.broadcast(any<Component>()) } returns 0
        every { Bukkit.getWorld(any<String>()) } returns null
        every { Bukkit.getPlayer(any<UUID>()) } returns null
    }

    @AfterEach
    fun cleanupBukkit() {
        unmockkStatic(Bukkit::class)
    }

    @Test
    fun `queued event is removed only after startup succeeds`() {
        val arena = arena("capture")
        val store = InMemoryEventQueueStore(listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)))
        val service = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        service.processQueue()

        assertNotNull(service.activeEvent)
        assertTrue(service.queuedEvents().isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `reload cancellation never advances the queued event`() {
        val activeArena = arena("active")
        val queuedArena = arena("queued")
        val store = InMemoryEventQueueStore(listOf(QueuedEvent(queuedArena.id, EventKind.SCHEDULED, now)))
        val service = service(
            store,
            LockState.UNLOCKED,
            mapOf(activeArena.id to activeArena, queuedArena.id to queuedArena),
        )
        assertTrue(service.startEvent(activeArena))

        service.shutdown(CancellationReason.RELOAD)
        service.processQueue()

        assertNull(service.activeEvent)
        assertEquals(listOf("queued"), service.queuedEvents().map { it.arenaId })
        assertEquals(listOf("queued"), store.load().map { it.arenaId })
    }

    @Test
    fun `failed ready to activating write prevents activation and restart starts once`() {
        val arena = arena("capture")
        val store = FailingQueueStore(
            initial = listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)),
            failWhen = { queue -> queue.singleOrNull()?.state == QueuedEventState.ACTIVATING },
        )
        val first = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        first.processQueue()

        assertNull(first.activeEvent)
        assertEquals(QueuedEventState.READY, store.load().single().state)
        verify(exactly = 0) { Bukkit.broadcast(any<Component>()) }

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()
        val startedId = restarted.activeEvent?.id

        assertNotNull(startedId)
        assertTrue(store.load().isEmpty())
        restarted.processQueue()
        assertEquals(startedId, restarted.activeEvent?.id)
    }

    @Test
    fun `hard crash with activating row resumes the same activation id exactly once`() {
        val arena = arena("capture")
        val activationId = UUID.randomUUID()
        val store = InMemoryEventQueueStore(
            listOf(
                QueuedEvent(
                    arenaId = arena.id,
                    startSource = EventKind.SCHEDULED,
                    scheduledAt = now,
                    state = QueuedEventState.ACTIVATING,
                    activationId = activationId,
                ),
            ),
        )

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()

        assertEquals(activationId, restarted.activeEvent?.id)
        assertTrue(store.load().isEmpty())
        restarted.processQueue()
        assertEquals(activationId, restarted.activeEvent?.id)
        verify(exactly = 1) { Bukkit.broadcast(any<Component>()) }
    }

    @Test
    fun `failed completed transition keeps activating durable and restart resumes same id`() {
        val arena = arena("capture")
        val store = FailingQueueStore(
            initial = listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)),
            failWhen = { queue -> queue.singleOrNull()?.state == QueuedEventState.COMPLETED },
        )
        val first = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        first.processQueue()
        val activationId = first.activeEvent?.id

        assertNotNull(activationId)
        assertEquals(QueuedEventState.ACTIVATING, store.load().single().state)
        assertEquals(activationId, store.load().single().activationId)

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()

        assertEquals(activationId, restarted.activeEvent?.id)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `completed marker surviving failed compaction never replays after restart`() {
        val arena = arena("capture")
        val store = FailingQueueStore(
            initial = listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)),
            failWhen = { queue -> queue.isEmpty() },
        )
        val first = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        first.processQueue()

        assertNotNull(first.activeEvent)
        assertEquals(QueuedEventState.COMPLETED, store.load().single().state)

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()

        assertNull(restarted.activeEvent)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `activation rejection and failed retry persistence remain recoverable after crash`() {
        val arena = arena("capture")
        val store = FailingQueueStore(
            initial = listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)),
            failWhen = { queue ->
                queue.singleOrNull()?.let { it.state == QueuedEventState.READY && it.attempts == 1 } == true
            },
        )
        var configReads = 0
        val first = service(
            store,
            LockState.UNLOCKED,
            mapOf(arena.id to arena),
            lockProvider = {
                if (configReads++ == 0) LockState.UNLOCKED else LockState.ALL_LOCKED
            },
        )

        first.processQueue()

        assertNull(first.activeEvent)
        val durable = store.load().single()
        assertEquals(QueuedEventState.ACTIVATING, durable.state)
        assertNotNull(durable.activationId)

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()

        assertEquals(durable.activationId, restarted.activeEvent?.id)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `scheduled queue row cannot activate until its durable occurrence claim is committed`() {
        val arena = arena("capture")
        val occurrenceId = "arena:capture:0:2026-08-06:12:00"
        val store = FileOperationalStateStore(
            scheduleFile = temp.resolve("schedule-claim-gate.dat").toFile(),
            queueFile = temp.resolve("queue-claim-gate.dat").toFile(),
            logger = { _, _ -> },
        )
        assertTrue(store.claim("start:$occurrenceId", now))
        store.save(
            listOf(
                QueuedEvent(
                    arenaId = arena.id,
                    startSource = EventKind.SCHEDULED,
                    scheduledAt = now,
                    occurrenceId = occurrenceId,
                ),
            ),
        )
        val service = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        service.processQueue()

        assertNull(service.activeEvent)
        assertEquals(QueuedEventState.READY, store.load().single().state)

        assertTrue(store.commit("start:$occurrenceId"))
        service.processQueue()

        assertNotNull(service.activeEvent)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `scheduled queue row remains authoritative if an old claim is already missing`() {
        val arena = arena("capture")
        val occurrenceId = "arena:capture:legacy-pruned"
        val store = FileOperationalStateStore(
            scheduleFile = temp.resolve("schedule-missing-claim.dat").toFile(),
            queueFile = temp.resolve("queue-missing-claim.dat").toFile(),
            logger = { _, _ -> },
        )
        store.save(
            listOf(
                QueuedEvent(
                    arenaId = arena.id,
                    startSource = EventKind.SCHEDULED,
                    scheduledAt = now,
                    occurrenceId = occurrenceId,
                ),
            ),
        )

        val service = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        service.processQueue()

        assertNotNull(service.activeEvent)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `scheduled lock beyond claim retention starts once and does not block next event`() {
        val alpha = arena("alpha")
        val beta = arena("beta")
        val alphaOccurrence = "arena:alpha:0:old"
        val betaOccurrence = "arena:beta:0:old"
        val store = FileOperationalStateStore(
            scheduleFile = temp.resolve("schedule-long-lock.dat").toFile(),
            queueFile = temp.resolve("queue-long-lock.dat").toFile(),
            logger = { _, _ -> },
        )
        assertTrue(store.claim("start:$alphaOccurrence", now, alpha.id, TeamMode.SOLO))
        assertTrue(store.commit("start:$alphaOccurrence"))
        assertTrue(store.claim("start:$betaOccurrence", now.plusSeconds(1), beta.id, TeamMode.SOLO))
        assertTrue(store.commit("start:$betaOccurrence"))
        store.save(
            listOf(
                QueuedEvent(alpha.id, EventKind.SCHEDULED, now, occurrenceId = alphaOccurrence),
                QueuedEvent(beta.id, EventKind.SCHEDULED, now.plusSeconds(1), occurrenceId = betaOccurrence),
            ),
        )

        val lockedAt = now.plus(Duration.ofDays(4))
        val lockedKoth = service(
            store,
            LockState.ALL_LOCKED,
            mapOf(alpha.id to alpha, beta.id to beta),
            clock = Clock.fixed(lockedAt, ZoneOffset.UTC),
        )
        val scheduler = ScheduleService(
            cfgLoader = {
                EnthusiaKothConfig(
                    schedule = ScheduleConfig(enabled = true, zone = ZoneOffset.UTC),
                )
            },
            kothService = lockedKoth,
            arenas = { mapOf(alpha.id to alpha, beta.id to beta) },
            stateStore = store,
            clock = Clock.fixed(lockedAt, ZoneOffset.UTC),
            warningSink = { _, _ -> },
            logger = {},
        )

        scheduler.tickAt(lockedAt)

        assertEquals(ScheduleClaimStatus.COMMITTED, store.claimStatus("start:$alphaOccurrence"))
        assertEquals(ScheduleClaimStatus.COMMITTED, store.claimStatus("start:$betaOccurrence"))
        assertEquals(listOf("alpha", "beta"), store.load().map { it.arenaId })

        val unlockedAt = lockedAt.plusSeconds(11)
        val unlocked = service(
            store,
            LockState.UNLOCKED,
            mapOf(alpha.id to alpha, beta.id to beta),
            clock = Clock.fixed(unlockedAt, ZoneOffset.UTC),
        )
        unlocked.processQueue()
        val alphaActivation = unlocked.activeEvent?.id

        assertNotNull(alphaActivation)
        assertEquals("alpha", unlocked.activeEvent?.arena?.id)
        unlocked.processQueue()
        assertEquals(alphaActivation, unlocked.activeEvent?.id)

        assertTrue(unlocked.forceEnd(announce = false))
        assertEquals("beta", unlocked.activeEvent?.arena?.id)
        verify(exactly = 2) { Bukkit.broadcast(any<Component>()) }
    }

    @Test
    fun `temporary lock failure leaves event queued with retry state`() {
        val arena = arena("capture")
        val store = InMemoryEventQueueStore(listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)))
        val service = service(store, LockState.ALL_LOCKED, mapOf(arena.id to arena))

        service.processQueue()

        assertEquals(1, service.queuedEvents().size)
        assertEquals(1, service.nextQueued()?.attempts)
        assertEquals(now.plusSeconds(10), service.nextQueued()?.nextAttemptAt)
        assertEquals(1, store.load().size)
    }

    @Test
    fun `temporary lock beyond old sixty retry ceiling still starts exactly once after unlock`() {
        val arena = arena("capture")
        val store = InMemoryEventQueueStore(
            listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now, teamMode = TeamMode.GUILD)),
        )

        repeat(65) { attempt ->
            val attemptTime = now.plusSeconds(attempt * 10L)
            service(
                store,
                LockState.ALL_LOCKED,
                mapOf(arena.id to arena),
                clock = Clock.fixed(attemptTime, ZoneOffset.UTC),
            ).processQueue()
        }

        assertEquals(65, store.load().single().attempts)
        assertEquals(TeamMode.GUILD, store.load().single().teamMode)

        val unlocked = service(
            store,
            LockState.UNLOCKED,
            mapOf(arena.id to arena),
            clock = Clock.fixed(now.plusSeconds(650), ZoneOffset.UTC),
        )
        unlocked.processQueue()
        val startedId = unlocked.activeEvent?.id

        assertNotNull(startedId)
        assertEquals(TeamMode.GUILD, unlocked.activeEvent?.teamMode)
        assertTrue(unlocked.queuedEvents().isEmpty())
        assertTrue(store.load().isEmpty())

        unlocked.processQueue()
        assertEquals(startedId, unlocked.activeEvent?.id)
    }

    @Test
    fun `private tests preserve mode access and quick versus production timing`() {
        val arena = arena("capture").copy(durationSeconds = 90, captureSeconds = 30)
        val cfg = EnthusiaKothConfig(
            privateTesting = PrivateTestingConfig(
                lobbySeconds = 0,
                quickMatchDurationSeconds = 12,
                quickCaptureSeconds = 4,
            ),
        )
        val production = service(InMemoryEventQueueStore(), LockState.UNLOCKED, mapOf(arena.id to arena))
        assertTrue(
            production.startPrivateTest(
                arena,
                UUID.randomUUID(),
                cfg,
                TeamMode.GUILD,
                PrivateTestAccess.OWNER_ONLY,
                quickTiming = false,
            ),
        )
        assertEquals(90, production.activeEvent?.arena?.durationSeconds)
        assertEquals(30, production.activeEvent?.arena?.captureSeconds)
        assertEquals(TeamMode.GUILD, production.activeEvent?.teamMode)
        assertEquals(PrivateTestAccess.OWNER_ONLY, production.activeEvent?.privateTestAccess)

        val quick = service(InMemoryEventQueueStore(), LockState.UNLOCKED, mapOf(arena.id to arena))
        assertTrue(
            quick.startPrivateTest(
                arena,
                UUID.randomUUID(),
                cfg,
                TeamMode.SOLO,
                PrivateTestAccess.PERMISSION_JOIN,
                quickTiming = true,
            ),
        )
        assertEquals(12, quick.activeEvent?.arena?.durationSeconds)
        assertEquals(4, quick.activeEvent?.arena?.captureSeconds)
        assertEquals(PrivateTestAccess.PERMISSION_JOIN, quick.activeEvent?.privateTestAccess)
    }

    @Test
    fun `conquest private testing remains rejected by legacy contract`() {
        val arena = arena("conquest", family = "conquest")
        val cfg = EnthusiaKothConfig(privateTesting = PrivateTestingConfig(lobbySeconds = 0))
        val service = service(InMemoryEventQueueStore(), LockState.UNLOCKED, mapOf(arena.id to arena))

        assertFalse(KothService.supportsPrivateTesting(arena))
        assertFalse(
            service.startPrivateTest(
                arena,
                UUID.randomUUID(),
                cfg,
                TeamMode.GUILD,
                PrivateTestAccess.OWNER_ONLY,
                quickTiming = true,
            ),
        )
        assertNull(service.activeEvent)
    }

    @Test
    fun `permanently invalid arena is logged and deliberately removed`() {
        val logs = mutableListOf<String>()
        val store = InMemoryEventQueueStore(listOf(QueuedEvent("removed", EventKind.SCHEDULED, now)))
        val service = service(store, LockState.UNLOCKED, emptyMap(), logs)

        service.processQueue()

        assertTrue(service.queuedEvents().isEmpty())
        assertTrue(logs.single().contains("permanently invalid"))
    }

    @Test
    fun `queue preserves insertion order and lock changes do not erase it`() {
        val alpha = arena("alpha")
        val beta = arena("beta")
        val store = InMemoryEventQueueStore()
        val service = service(store, LockState.ALL_LOCKED, mapOf(alpha.id to alpha, beta.id to beta))

        service.queueStart(alpha, EventKind.SCHEDULED, now)
        service.queueStart(beta, EventKind.SCHEDULED, now.plusSeconds(1))

        assertEquals(listOf("alpha", "beta"), service.queuedEvents().map { it.arenaId })
        assertEquals(listOf("alpha", "beta"), store.load().map { it.arenaId })
    }

    @Test
    fun `queue write failure rejects enqueue and leaves no volatile ghost entry`() {
        val arena = arena("capture")
        val store = object : EventQueueStore {
            override fun load(): List<QueuedEvent> = emptyList()
            override fun save(queue: List<QueuedEvent>) { error("disk full") }
        }
        val service = service(store, LockState.ALL_LOCKED, mapOf(arena.id to arena))

        assertFalse(service.queueStart(arena, EventKind.SCHEDULED, now))
        assertTrue(service.queuedEvents().isEmpty())
    }

    private fun service(
        store: EventQueueStore,
        lock: LockState,
        arenaMap: Map<String, KothArena>,
        logs: MutableList<String> = mutableListOf(),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        lockProvider: () -> LockState = { lock },
    ): KothService {
        val lang = mockk<LangService>(relaxed = true)
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        return KothService(
            cfgLoader = {
                EnthusiaKothConfig(
                    locks = LockConfig(lockProvider()),
                    display = DisplayConfig(zoneBorder = false),
                    discord = DiscordConfig(enabled = false),
                )
            },
            stats = mockk<SqlStatsRepository>(relaxed = true),
            economy = mockk<PlayerEconomy>(relaxed = true),
            guilds = mockk<LumaGuildsAdapter>(relaxed = true),
            displayService = mockk<DisplayService>(relaxed = true),
            fireworkService = mockk<FireworkCelebrationService>(relaxed = true),
            discordWebhook = mockk<DiscordWebhookService>(relaxed = true),
            zoneBorderService = mockk<ZoneBorderService>(relaxed = true),
            lang = lang,
            arenaResolver = arenaMap::get,
            queueStore = store,
            clock = clock,
            logger = { message, _ -> logs += message },
        )
    }

    private fun arena(id: String, family: String = "capture") = KothArena(
        id = id,
        family = family,
        zone = mockk<CaptureZone>(relaxed = true),
        durationSeconds = 60,
        captureSeconds = 10,
    )

    private class FailingQueueStore(
        initial: List<QueuedEvent>,
        private val failWhen: (List<QueuedEvent>) -> Boolean,
    ) : EventQueueStore {
        private var persisted = initial.toList()
        private var failed = false

        override fun load(): List<QueuedEvent> = persisted.toList()

        override fun save(queue: List<QueuedEvent>) {
            if (!failed && failWhen(queue)) {
                failed = true
                error("injected queue persistence failure")
            }
            persisted = queue.toList()
        }
    }
}
