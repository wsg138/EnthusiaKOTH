package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.TeamMode
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

class StartService(
    private val config: () -> EnthusiaKothConfig,
    private val pluginReady: () -> Boolean,
    private val hasConflictingEvent: () -> Boolean,
    private val economy: PlayerEconomy,
    private val starter: EventStarter,
    private val logError: (String, Throwable?) -> Unit,
    private val paymentJournal: PaymentJournal = NoopPaymentJournal,
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
        return attemptStart(arena, EventKind.ADMIN, 0, null, request.teamMode)
    }

    private fun startFlare(request: StartRequest, arena: KothArena, cfg: EnthusiaKothConfig): StartResult {
        if (!cfg.flares.enabled) return StartResult.Rejected(StartFailure.FEATURE_DISABLED)
        if (!request.actor.canUseFlare) return StartResult.Rejected(StartFailure.NO_PERMISSION)
        if (!cfg.locks.state.allows(EventKind.FLARE)) return StartResult.Rejected(StartFailure.LOCKED)
        if (hasConflictingEvent()) return StartResult.Rejected(StartFailure.ALREADY_ACTIVE)
        return attemptStart(arena, EventKind.FLARE, 0, null, request.teamMode)
    }

    private fun startPaid(request: StartRequest, arena: KothArena, cfg: EnthusiaKothConfig): StartResult {
        val manual = cfg.manualStart
        if (!manual.enabled) return StartResult.Rejected(StartFailure.FEATURE_DISABLED)
        val playerId = request.actor.playerId ?: return StartResult.Rejected(StartFailure.PLAYER_REQUIRED)

        // Permission grants access to a tier; it must never silently select the more expensive tier.
        val tier = request.tier ?: StartTier.BASIC
        val permitted = when (tier) {
            StartTier.BASIC -> request.actor.canStartBasic || request.actor.canStartAdvanced
            StartTier.ADVANCED -> request.actor.canStartAdvanced
        }
        if (!permitted) return StartResult.Rejected(StartFailure.NO_PERMISSION)

        val kind = if (request.source == StartSource.GUI) EventKind.GUI else EventKind.PLAYER_COMMAND
        if (!cfg.locks.state.allows(kind)) return StartResult.Rejected(StartFailure.LOCKED)
        if (hasConflictingEvent()) return StartResult.Rejected(StartFailure.ALREADY_ACTIVE)

        val cost = when (tier) {
            StartTier.BASIC -> manual.basicCost
            StartTier.ADVANCED -> manual.advancedCost
        }.coerceAtLeast(0.0)

        val available = try {
            economy.isAvailable()
        } catch (error: Throwable) {
            logError("Vault provider availability check threw for paid KOTH start", error)
            return StartResult.Rejected(StartFailure.ECONOMY_UNAVAILABLE, cost)
        }
        if (!available) return StartResult.Rejected(StartFailure.ECONOMY_UNAVAILABLE, cost)

        val balance = try {
            economy.balance(playerId)
        } catch (error: Throwable) {
            logError("Vault balance lookup threw for player $playerId", error)
            return StartResult.Rejected(StartFailure.ECONOMY_ERROR, cost)
        }
        if (balance + 1.0e-9 < cost) return StartResult.Rejected(StartFailure.INSUFFICIENT_FUNDS, cost, balance)

        val receipt = if (cost > 0.0) {
            val transactionId = UUID.randomUUID()
            val createdAt = java.time.Instant.now()
            val prepared = PaymentJournalEntry(
                transactionId = transactionId,
                payerId = playerId,
                amount = cost,
                source = request.source,
                status = PaymentJournalStatus.PREPARED,
                createdAt = createdAt,
            )
            if (!paymentJournal.record(prepared)) {
                logError("Refusing paid KOTH start because its payment journal entry could not be persisted", null)
                return StartResult.Rejected(StartFailure.PAYMENT_JOURNAL_FAILED, cost, balance)
            }

            val withdrawn = try {
                economy.withdraw(playerId, cost)
            } catch (error: Throwable) {
                // PREPARED is intentionally retained: an exception can happen after a
                // provider accepted the withdrawal, so startup must flag it as ambiguous.
                logError("Vault withdrawal threw for player $playerId amount $cost; payment journal remains PREPARED", error)
                return StartResult.Rejected(StartFailure.WITHDRAWAL_FAILED, cost, balance)
            }
            if (!withdrawn) {
                if (!paymentJournal.update(transactionId, PaymentJournalStatus.CANCELLED)) {
                    logError(
                        "Vault rejected the KOTH withdrawal, but its PREPARED journal entry could not be marked CANCELLED; manual reconciliation is required",
                        null,
                    )
                    return StartResult.Rejected(StartFailure.PAYMENT_JOURNAL_FAILED, cost, balance)
                }
                return StartResult.Rejected(StartFailure.WITHDRAWAL_FAILED, cost, balance)
            }

            val chargedReceipt = PaymentReceipt(
                payerId = playerId,
                amount = cost,
                source = request.source,
                transactionId = transactionId,
                journal = paymentJournal,
            )
            if (!paymentJournal.update(transactionId, PaymentJournalStatus.CHARGED)) {
                val refunded = refund(chargedReceipt, "payment journal transition failure", null)
                return StartResult.Rejected(
                    if (refunded) StartFailure.PAYMENT_JOURNAL_FAILED else StartFailure.REFUND_FAILED,
                    cost,
                    balance,
                )
            }
            chargedReceipt
        } else {
            null
        }
        return attemptStart(arena, kind, manual.delaySeconds, receipt, request.teamMode)
    }

    private fun attemptStart(
        arena: KothArena,
        kind: EventKind,
        delaySeconds: Int,
        receipt: PaymentReceipt?,
        teamMode: TeamMode,
    ): StartResult {
        val started = try {
            starter.start(arena, kind, delaySeconds, receipt, teamMode)
        } catch (error: Throwable) {
            return failedAfterPayment(StartFailure.START_THREW, receipt, error)
        }
        if (!started) return failedAfterPayment(StartFailure.START_FAILED, receipt, null)
        return StartResult.Started(receipt?.amount ?: 0.0)
    }

    private fun failedAfterPayment(
        failure: StartFailure,
        receipt: PaymentReceipt?,
        error: Throwable?,
    ): StartResult {
        if (receipt == null) {
            if (error != null) logError("KOTH start threw before completion", error)
            return StartResult.Rejected(failure)
        }
        val refunded = refund(receipt, "start failure", error)
        return if (refunded) {
            if (error != null) logError("KOTH start threw; payment was refunded", error)
            StartResult.Rejected(failure, receipt.amount)
        } else {
            StartResult.Rejected(StartFailure.REFUND_FAILED, receipt.amount)
        }
    }

    private fun refund(receipt: PaymentReceipt, reason: String, cause: Throwable?): Boolean {
        if (!receipt.beginRefund()) return receipt.isRefunded()
        val refunded = try {
            economy.deposit(receipt.payerId, receipt.amount)
        } catch (refundError: Throwable) {
            logError(
                "KOTH $reason refund threw for player ${receipt.payerId} amount ${receipt.amount}; journal remains REFUNDING for manual reconciliation",
                refundError,
            )
            return false
        }
        if (!receipt.completeRefund(refunded)) {
            logError(
                "KOTH $reason refund result could not be persisted for player ${receipt.payerId} amount ${receipt.amount}; durable state remains REFUNDING for manual reconciliation",
                cause,
            )
            return false
        }
        if (!refunded) {
            logError("KOTH $reason refund failed for player ${receipt.payerId} amount ${receipt.amount}", cause)
        }
        return refunded
    }
}

data class StartRequest(
    val actor: StartActor,
    val arena: KothArena?,
    val source: StartSource,
    val tier: StartTier? = null,
    val teamMode: TeamMode = TeamMode.SOLO,
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
    ECONOMY_ERROR,
    INSUFFICIENT_FUNDS,
    WITHDRAWAL_FAILED,
    START_FAILED,
    START_THREW,
    PAYMENT_JOURNAL_FAILED,
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
    /** Legacy/default entry point retained so existing adapters and tests remain source-compatible. */
    fun start(
        arena: KothArena,
        kind: EventKind,
        delaySeconds: Int,
        payment: PaymentReceipt?,
    ): Boolean

    /** Mode-aware production entry point. Implementations that care about team mode override this overload. */
    fun start(
        arena: KothArena,
        kind: EventKind,
        delaySeconds: Int,
        payment: PaymentReceipt?,
        teamMode: TeamMode,
    ): Boolean = start(arena, kind, delaySeconds, payment)
}

interface PlayerEconomy {
    fun isAvailable(): Boolean
    fun balance(playerId: UUID): Double
    fun withdraw(playerId: UUID, amount: Double): Boolean
    fun deposit(playerId: UUID, amount: Double): Boolean
}
