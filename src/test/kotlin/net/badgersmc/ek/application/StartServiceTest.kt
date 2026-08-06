package net.badgersmc.ek.application

import io.mockk.mockk
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.LockConfig
import net.badgersmc.ek.config.ManualStartConfig
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StartServiceTest {
    private val playerId = UUID.randomUUID()
    private val arena = mockk<KothArena>(relaxed = true)

    @Test
    fun `successful paid start withdraws exact decimal and passes configured delay`() {
        val economy = FakeEconomy(100.0)
        var observed: Triple<EventKind, Int, Double>? = null
        val service = service(economy, config(basicCost = 12.75, delay = 9)) { _, kind, delay ->
            observed = Triple(kind, delay, economy.currentBalance)
            true
        }

        val result = service.start(request())

        assertInstanceOf(StartResult.Started::class.java, result)
        assertEquals(87.25, economy.currentBalance, 0.000001)
        assertEquals(Triple(EventKind.PLAYER_COMMAND, 9, 87.25), observed)
    }

    @Test
    fun `insufficient funds never withdraws or starts`() {
        val economy = FakeEconomy(4.0)
        var starts = 0
        val result = service(economy, config(basicCost = 5.5)) { _, _, _ -> starts++; true }.start(request())

        assertEquals(StartFailure.INSUFFICIENT_FUNDS, (result as StartResult.Rejected).failure)
        assertEquals(4.0, economy.currentBalance)
        assertEquals(0, starts)
    }

    @Test
    fun `failed withdrawal does not attempt startup`() {
        val economy = FakeEconomy(20.0, withdrawSucceeds = false)
        var starts = 0
        val result = service(economy, config(basicCost = 5.0)) { _, _, _ -> starts++; true }.start(request())

        assertEquals(StartFailure.WITHDRAWAL_FAILED, (result as StartResult.Rejected).failure)
        assertEquals(0, starts)
        assertEquals(20.0, economy.currentBalance)
    }

    @Test
    fun `failed start refunds once`() {
        val economy = FakeEconomy(20.0)
        val result = service(economy, config(basicCost = 5.25)) { _, _, _ -> false }.start(request())

        assertEquals(StartFailure.START_FAILED, (result as StartResult.Rejected).failure)
        assertEquals(20.0, economy.currentBalance, 0.000001)
        assertEquals(1, economy.deposits)
    }

    @Test
    fun `thrown start refunds once`() {
        val economy = FakeEconomy(20.0)
        val result = service(economy, config(basicCost = 5.25)) { _, _, _ -> error("boom") }.start(request())

        assertEquals(StartFailure.START_THREW, (result as StartResult.Rejected).failure)
        assertEquals(20.0, economy.currentBalance, 0.000001)
        assertEquals(1, economy.deposits)
    }

    @Test
    fun `failed refund is reported to operator log`() {
        val economy = FakeEconomy(20.0, depositSucceeds = false)
        val errors = mutableListOf<String>()
        val result = service(economy, config(basicCost = 5.0), logger = { message, _ -> errors += message }) { _, _, _ -> false }
            .start(request())

        assertEquals(StartFailure.REFUND_FAILED, (result as StartResult.Rejected).failure)
        assertEquals(15.0, economy.currentBalance)
        assertTrue(errors.single().contains("refund failed"))
    }

    @Test
    fun `gui uses the same paid start flow`() {
        val economy = FakeEconomy(20.0)
        var kind: EventKind? = null
        val result = service(economy, config(basicCost = 3.0)) { _, observed, _ -> kind = observed; true }
            .start(request(source = StartSource.GUI))

        assertInstanceOf(StartResult.Started::class.java, result)
        assertEquals(EventKind.GUI, kind)
        assertEquals(17.0, economy.currentBalance)
    }

    @Test
    fun `admin and console starts are free`() {
        listOf(
            StartRequest(StartActor(playerId, isAdmin = true), arena, StartSource.ADMIN_COMMAND),
            StartRequest(StartActor(null, isConsole = true), arena, StartSource.CONSOLE),
        ).forEach { adminRequest ->
            val economy = FakeEconomy(20.0)
            var kind: EventKind? = null
            val result = service(economy, config(basicCost = 10.0)) { _, observed, _ -> kind = observed; true }
                .start(adminRequest)

            assertInstanceOf(StartResult.Started::class.java, result)
            assertEquals(EventKind.ADMIN, kind)
            assertEquals(20.0, economy.currentBalance)
            assertEquals(0, economy.withdrawals)
        }
    }

    @Test
    fun `manual lock blocks player command gui and flare before side effects`() {
        listOf(StartSource.PLAYER_COMMAND, StartSource.GUI, StartSource.FLARE).forEach { source ->
            val economy = FakeEconomy(20.0)
            var starts = 0
            val result = service(economy, config(basicCost = 5.0, lock = LockState.MANUAL_LOCKED)) { _, _, _ -> starts++; true }
                .start(request(source = source))

            assertEquals(StartFailure.LOCKED, (result as StartResult.Rejected).failure)
            assertEquals(20.0, economy.currentBalance)
            assertEquals(0, starts)
        }
    }

    @Test
    fun `manual start disabled blocks player and gui starts`() {
        listOf(StartSource.PLAYER_COMMAND, StartSource.GUI).forEach { source ->
            val economy = FakeEconomy(20.0)
            val result = service(economy, config(basicCost = 5.0, enabled = false)) { _, _, _ -> true }
                .start(request(source = source))
            assertEquals(StartFailure.FEATURE_DISABLED, (result as StartResult.Rejected).failure)
            assertEquals(20.0, economy.currentBalance)
        }
    }

    @Test
    fun `concurrent duplicate attempts cannot double charge or start`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val economy = FakeEconomy(20.0)
        val service = service(economy, config(basicCost = 5.0)) { _, _, _ ->
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            true
        }
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit<StartResult> { service.start(request()) }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val second = pool.submit<StartResult> { service.start(request()) }.get(1, TimeUnit.SECONDS)

        assertEquals(StartFailure.CONCURRENT_REQUEST, (second as StartResult.Rejected).failure)
        release.countDown()
        assertInstanceOf(StartResult.Started::class.java, first.get(1, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(15.0, economy.currentBalance)
        assertEquals(1, economy.withdrawals)
    }

    private fun request(source: StartSource = StartSource.PLAYER_COMMAND) = StartRequest(
        actor = StartActor(playerId, canStartBasic = true, canUseFlare = true),
        arena = arena,
        source = source,
        tier = StartTier.BASIC,
    )

    private fun config(
        basicCost: Double = 0.0,
        delay: Int = 0,
        lock: LockState = LockState.UNLOCKED,
        enabled: Boolean = true,
    ) = EnthusiaKothConfig(
        manualStart = ManualStartConfig(
            enabled = enabled,
            basicCost = basicCost,
            advancedCost = basicCost + 1.0,
            delaySeconds = delay,
        ),
        locks = LockConfig(lock),
    )

    private fun service(
        economy: FakeEconomy,
        config: EnthusiaKothConfig,
        logger: (String, Throwable?) -> Unit = { _, _ -> },
        starter: EventStarter,
    ) = StartService(
        config = { config },
        pluginReady = { true },
        hasConflictingEvent = { false },
        economy = economy,
        starter = starter,
        logError = logger,
    )

    private class FakeEconomy(
        var currentBalance: Double,
        private val withdrawSucceeds: Boolean = true,
        private val depositSucceeds: Boolean = true,
    ) : PlayerEconomy {
        var withdrawals = 0
        var deposits = 0

        override fun isAvailable() = true
        override fun balance(playerId: UUID) = currentBalance

        override fun withdraw(playerId: UUID, amount: Double): Boolean {
            withdrawals++
            if (!withdrawSucceeds) return false
            currentBalance -= amount
            return true
        }

        override fun deposit(playerId: UUID, amount: Double): Boolean {
            deposits++
            if (!depositSucceeds) return false
            currentBalance += amount
            return true
        }
    }
}
