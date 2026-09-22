package net.badgersmc.ek.application

import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaymentRecoveryPolicyTest {
    @Test
    fun `every journal status has an explicit fail-safe recovery action`() {
        val expected = mapOf(
            PaymentJournalStatus.REFUND_PENDING to PaymentRecoveryAction.AUTO_REFUND,
            PaymentJournalStatus.PREPARED to PaymentRecoveryAction.MANUAL_RECONCILIATION,
            PaymentJournalStatus.CHARGED to PaymentRecoveryAction.MANUAL_RECONCILIATION,
            PaymentJournalStatus.REFUNDING to PaymentRecoveryAction.MANUAL_RECONCILIATION,
            PaymentJournalStatus.REFUNDED to PaymentRecoveryAction.IGNORE,
            PaymentJournalStatus.SETTLED to PaymentRecoveryAction.IGNORE,
            PaymentJournalStatus.CANCELLED to PaymentRecoveryAction.IGNORE,
        )

        assertEquals(PaymentJournalStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, action) ->
            assertEquals(action, PaymentRecoveryPolicy.actionFor(status), status.name)
        }
    }

    @Test
    fun `recovery only auto refunds explicit refund-pending entries`() {
        val entries = PaymentJournalStatus.entries.mapIndexed { index, status -> entry(index, status) }
        val autoRefunded = mutableListOf<PaymentJournalStatus>()
        val manual = mutableListOf<PaymentJournalStatus>()

        PaymentRecoveryPolicy.recover(
            entries,
            autoRefund = { autoRefunded += it.status },
            manualReconciliation = { manual += it.status },
        )

        assertEquals(listOf(PaymentJournalStatus.REFUND_PENDING), autoRefunded)
        assertEquals(
            listOf(
                PaymentJournalStatus.PREPARED,
                PaymentJournalStatus.CHARGED,
                PaymentJournalStatus.REFUNDING,
            ),
            manual,
        )
    }

    @Test
    fun `terminal entries cause no recovery side effects`() {
        val terminal = listOf(
            entry(1, PaymentJournalStatus.REFUNDED),
            entry(2, PaymentJournalStatus.SETTLED),
            entry(3, PaymentJournalStatus.CANCELLED),
        )
        var calls = 0

        PaymentRecoveryPolicy.recover(
            terminal,
            autoRefund = { calls++ },
            manualReconciliation = { calls++ },
        )

        assertEquals(0, calls)
    }

    private fun entry(index: Int, status: PaymentJournalStatus) = PaymentJournalEntry(
        transactionId = UUID.nameUUIDFromBytes("transaction-$index".toByteArray()),
        payerId = UUID.nameUUIDFromBytes("payer-$index".toByteArray()),
        amount = 25.0,
        source = StartSource.ADMIN_COMMAND,
        status = status,
        createdAt = Instant.EPOCH,
    )
}
