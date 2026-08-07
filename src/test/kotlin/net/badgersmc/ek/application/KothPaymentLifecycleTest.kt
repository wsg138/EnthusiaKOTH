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
import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.InMemoryEventQueueStore
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `failed cancellation refund is retained as pending evidence`() {
        val payer = UUID.randomUUID()
        val journal = RecordingJournal()
        val transaction = UUID.randomUUID()
        journal.record(
            PaymentJournalEntry(
                transaction,
                payer,
                8.5,
                StartSource.PLAYER_COMMAND,
                PaymentJournalStatus.CHARGED,
                now,
            ),
        )
        val receipt = PaymentReceipt(payer, 8.5, StartSource.PLAYER_COMMAND, transaction, journal)
        val economy = FakeEconomy(refundSucceeds = false)
        val service = service(economy)
        assertTrue(service.startEvent(arena(), delaySeconds = 30, paymentReceipt = receipt))

        assertTrue(service.forceEnd(CancellationReason.ADMINISTRATIVE, announce = false))

        assertEquals(PaymentReceipt.State.REFUND_PENDING, receipt.state)
        assertEquals(PaymentJournalStatus.REFUND_PENDING, journal.entries().single().status)
        assertTrue(service.lastCancellationRefundPending)
        assertNull(service.activeEvent)
    }

    @Test
    fun `successful external refund with failed journal completion remains unresolved`() {
        val payer = UUID.randomUUID()
        val transaction = UUID.randomUUID()
        val journal = RecordingJournal(failUpdates = setOf(PaymentJournalStatus.REFUNDED))
        journal.record(
            PaymentJournalEntry(
                transaction,
                payer,
                8.5,
                StartSource.PLAYER_COMMAND,
                PaymentJournalStatus.CHARGED,
                now,
            ),
        )
        val receipt = PaymentReceipt(payer, 8.5, StartSource.PLAYER_COMMAND, transaction, journal)
        val economy = FakeEconomy(refundSucceeds = true)
        val service = service(economy)
        assertTrue(service.startEvent(arena(), delaySeconds = 30, paymentReceipt = receipt))

        assertTrue(service.forceEnd(CancellationReason.ADMINISTRATIVE, announce = false))

        assertEquals(PaymentReceipt.State.REFUNDING, receipt.state)
        assertEquals(PaymentJournalStatus.REFUNDING, journal.entries().single().status)
        assertTrue(service.lastCancellationRefundPending)
        assertEquals(listOf(payer to 8.5), economy.deposits)
        assertNull(service.activeEvent)
    }

    @Test
    fun `completed paid event with failed settlement is not automatically refunded on restart`() {
        val payer = UUID.randomUUID()
        val winnerId = UUID.randomUUID()
        val transaction = UUID.randomUUID()
        val journal = RecordingJournal(failUpdates = setOf(PaymentJournalStatus.SETTLED))
        journal.record(
            PaymentJournalEntry(
                transactionId = transaction,
                payerId = payer,
                amount = 15.0,
                source = StartSource.GUI,
                status = PaymentJournalStatus.CHARGED,
                createdAt = now,
            ),
        )
        val receipt = PaymentReceipt(payer, 15.0, StartSource.GUI, transaction, journal)
        val offlineWinner = mockk<Player>(relaxed = true)
        every { offlineWinner.name } returns "Winner"
        every { Bukkit.getOfflinePlayer(winnerId) } returns offlineWinner

        val service = service(FakeEconomy())
        assertTrue(service.startEvent(arena(), durationOverride = 0, kind = EventKind.GUI, paymentReceipt = receipt))
        service.activeEvent!!.scores[TeamId(TeamMode.SOLO, winnerId)] = 1.0

        service.tick()

        assertNull(service.activeEvent)
        assertEquals(PaymentReceipt.State.CHARGED, receipt.state)
        val durableEntry = journal.entries().single()
        assertEquals(PaymentJournalStatus.CHARGED, durableEntry.status)

        val restartedEconomy = FakeEconomy()
        PaymentRecoveryPolicy.recover(
            entries = journal.entries(),
            autoRefund = { entry -> restartedEconomy.deposit(entry.payerId, entry.amount) },
            manualReconciliation = {},
        )

        assertTrue(restartedEconomy.deposits.isEmpty())
    }

    @Test
    fun `reward command exception cannot strand a completed active event`() {
        val winnerId = UUID.randomUUID()
        val offlineWinner = mockk<Player>(relaxed = true)
        every { offlineWinner.name } returns "Winner"
        every { Bukkit.getOfflinePlayer(winnerId) } returns offlineWinner
        every { Bukkit.getConsoleSender() } returns mockk(relaxed = true)
        every { Bukkit.dispatchCommand(any(), any()) } throws IllegalStateException("reward integration failed")

        val terminated = mutableListOf<UUID>()
        val service = service(FakeEconomy(), eventTerminated = terminated::add)
        val rewardArena = arena().copy(durationSeconds = 0, rewards = listOf("say {PLAYER}"))
        assertTrue(service.startEvent(rewardArena, durationOverride = 0))
        val eventId = service.activeEvent!!.id
        service.activeEvent!!.scores[TeamId(TeamMode.SOLO, winnerId)] = 1.0

        service.tick()

        assertNull(service.activeEvent)
        assertEquals(listOf(eventId), terminated)
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

    private class RecordingJournal(
        private val failUpdates: Set<PaymentJournalStatus> = emptySet(),
    ) : PaymentJournal {
        private val records = linkedMapOf<UUID, PaymentJournalEntry>()
        override fun record(entry: PaymentJournalEntry): Boolean {
            records[entry.transactionId] = entry
            return true
        }
        override fun update(transactionId: UUID, status: PaymentJournalStatus): Boolean {
            if (status in failUpdates) return false
            val current = records[transactionId] ?: return false
            records[transactionId] = current.copy(status = status)
            return true
        }
        override fun entries(): List<PaymentJournalEntry> = records.values.toList()
    }

    private class FakeEconomy(private val refundSucceeds: Boolean = true) : PlayerEconomy {
        val deposits = mutableListOf<Pair<UUID, Double>>()
        override fun isAvailable() = true
        override fun balance(playerId: UUID) = 0.0
        override fun withdraw(playerId: UUID, amount: Double) = true
        override fun deposit(playerId: UUID, amount: Double): Boolean {
            deposits += playerId to amount
            return refundSucceeds
        }
    }
}
