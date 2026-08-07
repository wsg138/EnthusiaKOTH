package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.badgersmc.ek.config.DiscordConfig
import net.badgersmc.ek.config.DisplayConfig
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.InMemoryEventQueueStore
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class KothPaymentLifecycleTest {
    private val now = Instant.parse("2026-08-06T12:00:00Z")

    @BeforeEach
    fun mockBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns mutableListOf()
        every { Bukkit.broadcast(any<Component>()) } returns 0
        every { Bukkit.getWorld(any<String>()) } returns null
    }

    @AfterEach
    fun cleanupBukkit() = unmockkStatic(Bukkit::class)

    @Test
    fun `administrative cancellation refunds accepted paid event exactly once`() {
        val payer = UUID.randomUUID()
        val economy = FakeEconomy()
        val service = service(economy)
        val receipt = PaymentReceipt(payer, 12.75, StartSource.GUI)
        assertTrue(service.startEvent(arena(), kind = EventKind.GUI, delaySeconds = 30, paymentReceipt = receipt))

        assertTrue(service.forceEnd(CancellationReason.ADMINISTRATIVE))
        assertEquals(PaymentReceipt.State.REFUNDED, receipt.state)
        assertEquals(listOf(payer to 12.75), economy.deposits)
        assertTrue(!service.forceEnd(CancellationReason.PLUGIN_DISABLE, announce = false))
        assertEquals(1, economy.deposits.size)
    }

    @Test
    fun `every terminal path emits event cleanup exactly once`() {
        val terminated = mutableListOf<UUID>()
        val service = service(FakeEconomy(), eventTerminated = terminated::add)
        assertTrue(service.startEvent(arena()))
        val eventId = service.activeEvent!!.id

        assertTrue(service.forceEnd(CancellationReason.ADMINISTRATIVE, announce = false))

        assertEquals(listOf(eventId), terminated)
    }

    @Test
    fun `reload cancellation refunds without force-ended broadcast path`() {
        val payer = UUID.randomUUID()
        val economy = FakeEconomy()
        val service = service(economy)
        val receipt = PaymentReceipt(payer, 5.0, StartSource.PLAYER_COMMAND)
        assertTrue(service.startEvent(arena(), delaySeconds = 30, paymentReceipt = receipt))

        service.shutdown(CancellationReason.RELOAD)

        assertEquals(PaymentReceipt.State.REFUNDED, receipt.state)
        assertEquals(listOf(payer to 5.0), economy.deposits)
    }

    private fun service(economy: PlayerEconomy, eventTerminated: (UUID) -> Unit = {}): KothService {
        val lang = mockk<LangService>(relaxed = true)
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        return KothService(
            cfgLoader = { EnthusiaKothConfig(display = DisplayConfig(false), discord = DiscordConfig(false)) },
            stats = mockk<SqlStatsRepository>(relaxed = true),
            economy = economy,
            guilds = mockk<LumaGuildsAdapter>(relaxed = true),
            displayService = mockk<DisplayService>(relaxed = true),
            fireworkService = mockk<FireworkCelebrationService>(relaxed = true),
            discordWebhook = mockk<DiscordWebhookService>(relaxed = true),
            zoneBorderService = mockk<ZoneBorderService>(relaxed = true),
            lang = lang,
            arenaResolver = { null },
            queueStore = InMemoryEventQueueStore(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            logger = { _, _ -> },
            eventTerminated = eventTerminated,
        )
    }

    private fun arena() = KothArena(
        id = "capture",
        family = "capture",
        zone = mockk<CaptureZone>(relaxed = true),
        durationSeconds = 60,
        captureSeconds = 10,
    )

    private class FakeEconomy : PlayerEconomy {
        val deposits = mutableListOf<Pair<UUID, Double>>()
        override fun isAvailable() = true
        override fun balance(playerId: UUID) = 0.0
        override fun withdraw(playerId: UUID, amount: Double) = true
        override fun deposit(playerId: UUID, amount: Double): Boolean {
            deposits += playerId to amount
            return true
        }
    }
}
