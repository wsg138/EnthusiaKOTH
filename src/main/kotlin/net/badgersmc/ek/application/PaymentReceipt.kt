package net.badgersmc.ek.application

import java.util.UUID

/**
 * Tracks one accepted paid start through its complete lifecycle.
 *
 * State transitions are synchronized so overlapping shutdown, reload, command,
 * and activation-failure paths can never refund the same withdrawal twice.
 */
class PaymentReceipt(
    val payerId: UUID,
    val amount: Double,
    val source: StartSource,
) {
    enum class State { CHARGED, REFUNDING, REFUNDED, SETTLED }

    @Volatile
    var state: State = State.CHARGED
        private set

    @Synchronized
    fun beginRefund(): Boolean {
        if (state != State.CHARGED) return false
        state = State.REFUNDING
        return true
    }

    @Synchronized
    fun completeRefund(success: Boolean) {
        if (state != State.REFUNDING) return
        state = if (success) State.REFUNDED else State.CHARGED
    }

    @Synchronized
    fun settle(): Boolean {
        if (state != State.CHARGED) return false
        state = State.SETTLED
        return true
    }

    fun isRefunded(): Boolean = state == State.REFUNDED
    fun isOutstanding(): Boolean = state == State.CHARGED || state == State.REFUNDING
}
