package net.badgersmc.ek.application

enum class PaymentRecoveryAction {
    AUTO_REFUND,
    MANUAL_RECONCILIATION,
    IGNORE,
}

/**
 * Startup recovery must only repeat an economy deposit when the durable journal
 * explicitly proves that a refund is owed. CHARGED is intentionally ambiguous:
 * it can mean an interrupted paid start, or a successfully completed KOTH whose
 * final SETTLED journal write failed. Automatically refunding that state can
 * therefore return money for an event that already happened.
 */
object PaymentRecoveryPolicy {
    fun actionFor(status: PaymentJournalStatus): PaymentRecoveryAction = when (status) {
        PaymentJournalStatus.REFUND_PENDING -> PaymentRecoveryAction.AUTO_REFUND
        PaymentJournalStatus.PREPARED,
        PaymentJournalStatus.CHARGED,
        PaymentJournalStatus.REFUNDING -> PaymentRecoveryAction.MANUAL_RECONCILIATION
        PaymentJournalStatus.REFUNDED,
        PaymentJournalStatus.SETTLED,
        PaymentJournalStatus.CANCELLED -> PaymentRecoveryAction.IGNORE
    }

    fun recover(
        entries: Iterable<PaymentJournalEntry>,
        autoRefund: (PaymentJournalEntry) -> Unit,
        manualReconciliation: (PaymentJournalEntry) -> Unit,
    ) {
        entries.forEach { entry ->
            when (actionFor(entry.status)) {
                PaymentRecoveryAction.AUTO_REFUND -> autoRefund(entry)
                PaymentRecoveryAction.MANUAL_RECONCILIATION -> manualReconciliation(entry)
                PaymentRecoveryAction.IGNORE -> Unit
            }
        }
    }
}
