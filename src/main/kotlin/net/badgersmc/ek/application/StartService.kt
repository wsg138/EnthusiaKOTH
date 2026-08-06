package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

class StartService(
    private val config: () -> EnthusiaKothConfig,
    private val pluginReady: () -> Boolean,
    private val hasConflictingEvent: () -> Boolean,
    private val economy: PlayerEconomy,
    private val starter: EventStarter,
    private val logError: (String, Throwable?) -> Unit,
) {
    private val gate = ReentrantLock()

    fun start(request: StartRequest): StartResult {
        if (!gate.tryLock()) return StartResult.Rejected(StartFailure.CONCURRENT_REQUEST)
        try {
            if (!pluginReady()) return StartResult.Rejected(StartFailure.PLUGIN_NOT_READY)
            val cfg = config()
            val arena = request.arena ?: return StartResult.Rejected(StartFailure.INVALID_ARENA)
            return when (request.source) {
                StartSource.ADMIN_COMMAND, StartSource.CONSOLE -> startAdministrative(request, arena, cfg)
                StartSource.FLARE -> startFlare(request, arena, cfg)
                StartSource.PLAYER_COMMAND, StartSource.GUI -> startPaid(request, arena, cfg)
            }
        } finally {
            gate.unlock()
        }
    }

    private fun startAdministrative(request: StartRequest, arena: KothArena, cfg: EnthusiaKothConfig): StartResult {
        if (!request.actor.isConsole && !request.actor.isAdmin) return StartResult.Rejected(StartFailure.NO_PERMISSION)
        if (!cfg.locks.state.allows(EventKind.ADMIN)) return StartResult.Rejected(StartFailure.LOCKED)
        if (hasConflictingEvent()) return StartResult.Rejected(StartFailure.ALREADY_ACTIVE)
        return attemptStart(arena, EventKind.ADMIN, 0, null, 0.0)
    }

    private fun startFlare(request: StartRequest, arena: KothArena, cfg: EnthusiaKothConfig): StartResult {
        if (!cfg.flares.enabled) return StartResult.Rejected(StartFailure.FEATURE_DISABLED)
        if (!request.actor.canUseFlare) return StartResult.Rejected(StartFailure.NO_PERMISSION)
        if (!cfg.locks.state.allows(EventKind.FLARE)) return StartResult.Rejected(StartFailure.LOCKED)
        if (hasConflictingEvent()) return StartResult.Rejected(StartFailure.ALREADY_ACTIVE)
        return attemptStart(arena, EventKind.FLARE, 0, null, 0.0)
    }

    private fun startPaid(request: StartRequest, arena: KothArena, cfg: EnthusiaKothConfig): StartResult {
        val manual = cfg.manualStart
        if (!manual.enabled) return StartResult.Rejected(StartFailure.FEATURE_DISABLED)
        val playerId = request.actor.playerId ?: return StartResult.Rejected(StartFailure.PLAYER_REQUIRED)
        val tier = request.tier ?: if (request.actor.canStartAdvanced) StartTier.ADVANCED else StartTier.BASIC
        val permitted = when (tier) {
            StartTier.BASIC -> request.actor.canStartBasic || request.actor.canStartAdvanced
            StartTier.ADVANCED -> request.actor.canStartAdvanced
        }
        if (!permitted) return StartResult.Rejected(StartFailure.NO_PERMISSION)
        val kind = if (request.source == StartSource.GUI) EventKind.GUI else EventKind.PLAYER_COMMAND
        if (!cfg.locks.state.allows(kind)) return StartResult.Rejected(StartFailure.LOCKED)
        if (hasConflictingEvent()) return StartResult.Rejected(StartFailure.ALREADY_ACTIVE)
        if (!economy.isAvailable()) return StartResult.Rejected(StartFailure.ECONOMY_UNAVAILABLE)
        val cost = when (tier) {
            StartTier.BASIC -> manual.basicCost
            StartTier.ADVANCED -> manual.advancedCost
        }.coerceAtLeast(0.0)
        val balance = economy.balance(playerId)
        if (balance + 1.0e-9 < cost) return StartResult.Rejected(StartFailure.INSUFFICIENT_FUNDS, cost, balance)
        if (cost > 0.0 && !economy.withdraw(playerId, cost)) {
            return StartResult.Rejected(StartFailure.WITHDRAWAL_FAILED, cost, balance)
        }
        return attemptStart(arena, kind, manual.delaySeconds, playerId, cost)
    }

    private fun attemptStart(
        arena: KothArena,
        kind: EventKind,
        delaySeconds: Int,
        paidPlayer: UUID?,
        cost: Double,
    ): StartResult {
        val started = try {
            starter.start(arena, kind, delaySeconds)
        } catch (error: Throwable) {
            return failedAfterPayment(StartFailure.START_THREW, paidPlayer, cost, error)
        }
        if (!started) return failedAfterPayment(StartFailure.START_FAILED, paidPlayer, cost, null)
        return StartResult.Started(cost)
    }

    private fun failedAfterPayment(
        failure: StartFailure,
        paidPlayer: UUID?,
        cost: Double,
        error: Throwable?,
    ): StartResult {
        if (paidPlayer == null || cost <= 0.0) {
            if (error != null) logError("KOTH start threw before completion", error)
            return StartResult.Rejected(failure, cost)
        }
        val refunded = try {
            economy.deposit(paidPlayer, cost)
        } catch (refundError: Throwable) {
            logError("KOTH start refund threw for player $paidPlayer amount $cost", refundError)
            false
        }
        if (!refunded) {
            logError("KOTH start refund failed for player $paidPlayer amount $cost", error)
            return StartResult.Rejected(StartFailure.REFUND_FAILED, cost)
        }
        if (error != null) logError("KOTH start threw; payment was refunded", error)
        return StartResult.Rejected(failure, cost)
    }
}

data class StartRequest(
    val actor: StartActor,
    val arena: KothArena?,
    val source: StartSource,
    val tier: StartTier? = null,
)

data class StartActor(
    val playerId: UUID?,
    val isConsole: Boolean = false,
    val isAdmin: Boolean = false,
    val canStartBasic: Boolean = false,
    val canStartAdvanced: Boolean = false,
    val canUseFlare: Boolean = false,
)

enum class StartSource { PLAYER_COMMAND, GUI, FLARE, ADMIN_COMMAND, CONSOLE }
enum class StartTier { BASIC, ADVANCED }

enum class StartFailure {
    PLUGIN_NOT_READY,
    FEATURE_DISABLED,
    NO_PERMISSION,
    LOCKED,
    INVALID_ARENA,
    ALREADY_ACTIVE,
    PLAYER_REQUIRED,
    ECONOMY_UNAVAILABLE,
    INSUFFICIENT_FUNDS,
    WITHDRAWAL_FAILED,
    START_FAILED,
    START_THREW,
    REFUND_FAILED,
    CONCURRENT_REQUEST,
}

sealed interface StartResult {
    data class Started(val cost: Double) : StartResult
    data class Rejected(
        val failure: StartFailure,
        val cost: Double = 0.0,
        val balance: Double? = null,
    ) : StartResult
}

fun interface EventStarter {
    fun start(arena: KothArena, kind: EventKind, delaySeconds: Int): Boolean
}

interface PlayerEconomy {
    fun isAvailable(): Boolean
    fun balance(playerId: UUID): Double
    fun withdraw(playerId: UUID, amount: Double): Boolean
    fun deposit(playerId: UUID, amount: Double): Boolean
}
