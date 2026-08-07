package net.badgersmc.ek.application

enum class PendingRefundRecoveryResult {
    REFUNDED,
    ECONOMY_UNAVAILABLE,
    JOURNAL_TRANSITION_FAILED,
    REFUND_REJECTED,
    AMBIGUOUS_EXTERNAL_RESULT,
    REFUNDED_JOURNAL_FAILED,
}

data class PendingRefundRecoveryAttempt(
    val entry: PaymentJournalEntry,
    val result: PendingRefundRecoveryResult,
)

/**
 * Retries only durable REFUND_PENDING entries.
 *
 * Once a record enters REFUNDING, an exception from the external economy call
 * is treated as ambiguous and is never retried automatically. The provider may
 * have completed the deposit before throwing, so replaying it could duplicate
 * money. A definite false result is safe to return to REFUND_PENDING and retry.
 */
class PendingRefundRecovery(
    private val journal: PaymentJournal,
    private val economy: PlayerEconomy,
    private val logger: (String, Throwable?) -> Unit,
) {
    @Synchronized
    fun retryPending(): List<PendingRefundRecoveryAttempt> {
        val pending = journal.entries().filter { it.status == PaymentJournalStatus.REFUND_PENDING }
        if (pending.isEmpty()) return emptyList()

        val available = try {
            economy.isAvailable()
        } catch (error: Throwable) {
            logger("Vault availability check threw during pending KOTH refund recovery", error)
            false
        }
        if (!available) {
            return pending.map { PendingRefundRecoveryAttempt(it, PendingRefundRecoveryResult.ECONOMY_UNAVAILABLE) }
        }

        return pending.map { entry -> PendingRefundRecoveryAttempt(entry, recover(entry)) }
    }

    private fun recover(entry: PaymentJournalEntry): PendingRefundRecoveryResult {
        if (!journal.update(entry.transactionId, PaymentJournalStatus.REFUNDING)) {
            return PendingRefundRecoveryResult.JOURNAL_TRANSITION_FAILED
        }

        val refunded = try {
            economy.deposit(entry.payerId, entry.amount)
        } catch (error: Throwable) {
            logger(
                "KOTH pending refund threw for ${entry.payerId} amount ${entry.amount}; leaving ${entry.transactionId} REFUNDING for manual reconciliation",
                error,
            )
            return PendingRefundRecoveryResult.AMBIGUOUS_EXTERNAL_RESULT
        }

        if (!refunded) {
            return if (journal.update(entry.transactionId, PaymentJournalStatus.REFUND_PENDING)) {
                PendingRefundRecoveryResult.REFUND_REJECTED
            } else {
                PendingRefundRecoveryResult.JOURNAL_TRANSITION_FAILED
            }
        }

        return if (journal.update(entry.transactionId, PaymentJournalStatus.REFUNDED)) {
            PendingRefundRecoveryResult.REFUNDED
        } else {
            // The external deposit succeeded. FilePaymentJournal restores the
            // previous durable REFUNDING state when this write fails, so later
            // automatic scans will not replay the deposit.
            PendingRefundRecoveryResult.REFUNDED_JOURNAL_FAILED
        }
    }
}
