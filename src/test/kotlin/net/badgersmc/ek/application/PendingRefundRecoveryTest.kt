package net.badgersmc.ek.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PendingRefundRecoveryTest {
    @Test
    fun `pending refund waits for provider then refunds exactly once`() {
        val journal = FakeJournal(pendingEntry())
        val economy = FakeEconomy(available = false)
        val recovery = PendingRefundRecovery(journal, economy) { _, _ -> }

        val unavailable = recovery.retryPending()
        assertEquals(PendingRefundRecoveryResult.ECONOMY_UNAVAILABLE, unavailable.single().result)
        assertEquals(PaymentJournalStatus.REFUND_PENDING, journal.entry.status)
        assertEquals(0, economy.deposits)

        economy.available = true
        val recovered = recovery.retryPending()
        assertEquals(PendingRefundRecoveryResult.REFUNDED, recovered.single().result)
        assertEquals(PaymentJournalStatus.REFUNDED, journal.entry.status)
        assertEquals(1, economy.deposits)

        assertTrue(recovery.retryPending().isEmpty())
        assertEquals(1, economy.deposits)
    }

    @Test
    fun `provider exception leaves refunding ambiguous and is never replayed`() {
        val journal = FakeJournal(pendingEntry())
        val economy = FakeEconomy(available = true, throwOnDeposit = true)
        val recovery = PendingRefundRecovery(journal, economy) { _, _ -> }

        val result = recovery.retryPending()

        assertEquals(PendingRefundRecoveryResult.AMBIGUOUS_EXTERNAL_RESULT, result.single().result)
        assertEquals(PaymentJournalStatus.REFUNDING, journal.entry.status)
        assertEquals(1, economy.deposits)

        economy.throwOnDeposit = false
        assertTrue(recovery.retryPending().isEmpty())
        assertEquals(1, economy.deposits)
    }

    private fun pendingEntry() = PaymentJournalEntry(
        transactionId = UUID.randomUUID(),
        payerId = UUID.randomUUID(),
        amount = 25.0,
        source = StartSource.PLAYER_COMMAND,
        status = PaymentJournalStatus.REFUND_PENDING,
        createdAt = Instant.parse("2026-08-06T12:00:00Z"),
    )

    private class FakeJournal(initial: PaymentJournalEntry) : PaymentJournal {
        var entry = initial
        override fun record(entry: PaymentJournalEntry): Boolean = false
        override fun update(transactionId: UUID, status: PaymentJournalStatus): Boolean {
            if (transactionId != entry.transactionId) return false
            entry = entry.copy(status = status)
            return true
        }
        override fun entries(): List<PaymentJournalEntry> = listOf(entry)
    }

    private class FakeEconomy(
        var available: Boolean,
        var throwOnDeposit: Boolean = false,
    ) : PlayerEconomy {
        var deposits = 0
        override fun isAvailable(): Boolean = available
        override fun balance(playerId: UUID): Double = 0.0
        override fun withdraw(playerId: UUID, amount: Double): Boolean = false
        override fun deposit(playerId: UUID, amount: Double): Boolean {
            deposits++
            if (throwOnDeposit) error("provider boom")
            return true
        }
    }
}
