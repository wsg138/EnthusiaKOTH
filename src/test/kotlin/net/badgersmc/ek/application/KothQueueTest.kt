package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.badgersmc.ek.config.DiscordConfig
import net.badgersmc.ek.config.DisplayConfig
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.LockConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.EventQueueStore
import net.badgersmc.ek.infrastructure.persistence.InMemoryEventQueueStore
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KothQueueTest {
    private val now = Instant.parse("2026-08-06T12:00:00Z")

    @BeforeEach
    fun mockBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns mutableListOf()
        every { Bukkit.broadcast(any<Component>()) } returns 0
        every { Bukkit.getWorld(any<String>()) } returns null
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
    fun `temporary lock failure leaves event queued with bounded retry state`() {
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

        assertTrue(!service.queueStart(arena, EventKind.SCHEDULED, now))
        assertTrue(service.queuedEvents().isEmpty())
    }

    private fun service(
        store: EventQueueStore,
        lock: LockState,
        arenaMap: Map<String, KothArena>,
        logs: MutableList<String> = mutableListOf(),
    ): KothService {
        val lang = mockk<LangService>(relaxed = true)
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        return KothService(
            cfgLoader = {
                EnthusiaKothConfig(
                    locks = LockConfig(lock),
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
            clock = Clock.fixed(now, ZoneOffset.UTC),
            logger = { message, _ -> logs += message },
        )
    }

    private fun arena(id: String) = KothArena(
        id = id,
        family = "capture",
        zone = mockk<CaptureZone>(relaxed = true),
        durationSeconds = 60,
        captureSeconds = 10,
    )
}
