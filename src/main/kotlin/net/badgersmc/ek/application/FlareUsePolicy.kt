package net.badgersmc.ek.application

enum class FlareHand { MAIN, OFF }

data class FlareConsumption(val newAmount: Int, val clearHand: FlareHand?)

object FlareUsePolicy {
    fun rejectionKey(result: StartResult.Rejected): String = when (result.failure) {
        StartFailure.FEATURE_DISABLED -> "flare.disabled"
        StartFailure.LOCKED -> "flare.locked"
        StartFailure.ALREADY_ACTIVE -> "flare.already_active"
        StartFailure.NO_PERMISSION -> "command.error.no_permission"
        else -> "flare.failed"
    }

    fun consumption(amount: Int, hand: FlareHand): FlareConsumption {
        val remaining = (amount - 1).coerceAtLeast(0)
        return FlareConsumption(remaining, hand.takeIf { remaining == 0 })
    }
}
