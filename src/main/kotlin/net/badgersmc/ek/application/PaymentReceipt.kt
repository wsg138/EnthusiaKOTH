package net.badgersmc.ek.application

import java.time.Instant
import java.util.UUID

enum class PaymentJournalStatus {
    PREPARED,
    CHARGED,
    REFUND_PENDING,
    REFUNDING,
    REFUNDED,
    SETTLED,
    CANCELLED,
}

data class PaymentJournalEntry(
    val transactionId: UUID,
    val payerId: UUID,
    val amount: Double,
    val source: StartSource,
    val status: PaymentJournalStatus,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
)

interface PaymentJournal {
    fun record(entry: PaymentJournalEntry): Boolean
    fun update(transactionId: UUID, status: PaymentJournalStatus): Boolean
    fun entries(): List<PaymentJournalEntry>
}

object NoopPaymentJournal : PaymentJournal {
    override fun record(entry: PaymentJournalEntry): Boolean = true
    override fun update(transactionId: UUID, status: PaymentJournalStatus): Boolean = true
    override fun entries(): List<PaymentJournalEntry> = emptyList()
}

/**
 * Tracks one accepted paid start through its complete lifecycle.
 *
 * Every production transition is mirrored into the payment journal before an
 * external refund is attempted. A crash during an economy call therefore
 * leaves REFUNDING/PREPARED evidence for manual reconciliation instead of
 * risking a duplicate refund on restart.
 */
class PaymentReceipt(
    val payerId: UUID,
    val amount: Double,
    val source: StartSource,
    val transactionId: UUID = UUID.randomUUID(),
    private val journal: PaymentJournal = NoopPaymentJournal,
) {
    enum class State { CHARGED, REFUNDING, REFUND_PENDING, REFUNDED, SETTLED }

    @Volatile
    var state: State = State.CHARGED
        private set

    @Synchronized
    fun beginRefund(): Boolean {
        if (state != State.CHARGED && state != State.REFUND_PENDING) return false
        if (!journal.update(transactionId, PaymentJournalStatus.REFUNDING)) return false
        state = State.REFUNDING
        return true
    }

    @Synchronized
    fun completeRefund(success: Boolean): Boolean {
        if (state != State.REFUNDING) return false
        val nextState = if (success) State.REFUNDED else State.REFUND_PENDING
        val journalState = if (success) PaymentJournalStatus.REFUNDED else PaymentJournalStatus.REFUND_PENDING
        if (!journal.update(transactionId, journalState)) return false
        state = nextState
        return true
    }

    @Synchronized
    fun settle(): Boolean {
        if (state != State.CHARGED) return false
        if (!journal.update(transactionId, PaymentJournalStatus.SETTLED)) return false
        state = State.SETTLED
        return true
    }

    fun isRefunded(): Boolean = state == State.REFUNDED

    fun isOutstanding(): Boolean =
        state == State.CHARGED || state == State.REFUNDING || state == State.REFUND_PENDING
}
