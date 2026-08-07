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
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `successful paid start withdraws exact decimal and attaches receipt`() {
        val economy = FakeEconomy(100.0)
        var observed: PaymentReceipt? = null
        val service = service(economy, config(basicCost = 12.75, delay = 9)) { _, kind, delay, receipt ->
            assertEquals(EventKind.PLAYER_COMMAND, kind)
            assertEquals(9, delay)
            observed = receipt
            true
        }

        val result = service.start(request())

        assertInstanceOf(StartResult.Started::class.java, result)
        assertEquals(87.25, economy.currentBalance, 0.000001)
        assertEquals(playerId, observed?.payerId)
        assertEquals(12.75, observed?.amount)
        assertEquals(PaymentReceipt.State.CHARGED, observed?.state)
    }

    @Test
    fun `unspecified gui tier defaults to basic even for advanced user`() {
        val economy = FakeEconomy(100.0)
        val service = service(economy, config(basicCost = 5.0, advancedCost = 25.0)) { _, _, _, _ -> true }
        val result = service.start(
            StartRequest(
                actor = StartActor(playerId, canStartBasic = true, canStartAdvanced = true),
                arena = arena,
                source = StartSource.GUI,
                tier = null,
            ),
        )

        assertInstanceOf(StartResult.Started::class.java, result)
        assertEquals(95.0, economy.currentBalance)
    }

    @Test
    fun `explicit advanced tier charges advanced price`() {
        val economy = FakeEconomy(100.0)
        val service = service(economy, config(basicCost = 5.0, advancedCost = 25.0)) { _, _, _, _ -> true }
        val result = service.start(
            StartRequest(
                actor = StartActor(playerId, canStartAdvanced = true),
                arena = arena,
                source = StartSource.PLAYER_COMMAND,
                tier = StartTier.ADVANCED,
            ),
        )

        assertInstanceOf(StartResult.Started::class.java, result)
        assertEquals(75.0, economy.currentBalance)
    }

    @Test
    fun `insufficient funds never withdraws or starts`() {
        val economy = FakeEconomy(4.0)
        var starts = 0
        val result = service(economy, config(basicCost = 5.5)) { _, _, _, _ -> starts++; true }.start(request())

        assertEquals(StartFailure.INSUFFICIENT_FUNDS, (result as StartResult.Rejected).failure)
        assertEquals(4.0, economy.currentBalance)
        assertEquals(0, starts)
    }

    @Test
    fun `balance provider exception is controlled and never withdraws`() {
        val economy = FakeEconomy(20.0, balanceThrows = true)
        var starts = 0
        val errors = mutableListOf<String>()
        val result = service(economy, config(basicCost = 5.0), { message, _ -> errors += message }) { _, _, _, _ -> starts++; true }
            .start(request())

        assertEquals(StartFailure.ECONOMY_ERROR, (result as StartResult.Rejected).failure)
        assertEquals(0, economy.withdrawals)
        assertEquals(0, starts)
        assertTrue(errors.single().contains("balance lookup"))
    }

    @Test
    fun `withdraw provider exception is controlled and never starts`() {
        val economy = FakeEconomy(20.0, withdrawThrows = true)
        var starts = 0
        val result = service(economy, config(basicCost = 5.0)) { _, _, _, _ -> starts++; true }.start(request())

        assertEquals(StartFailure.WITHDRAWAL_FAILED, (result as StartResult.Rejected).failure)
        assertEquals(0, starts)
        assertEquals(20.0, economy.currentBalance)
    }

    @Test
    fun `failed start refunds once through receipt`() {
        val economy = FakeEconomy(20.0)
        var receipt: PaymentReceipt? = null
        val result = service(economy, config(basicCost = 5.25)) { _, _, _, observed -> receipt = observed; false }.start(request())

        assertEquals(StartFailure.START_FAILED, (result as StartResult.Rejected).failure)
        assertEquals(20.0, economy.currentBalance, 0.000001)
        assertEquals(1, economy.deposits)
        assertEquals(PaymentReceipt.State.REFUNDED, receipt?.state)
    }

    @Test
    fun `starter-side refund cannot be duplicated by start failure handling`() {
        val economy = FakeEconomy(20.0)
        val result = service(economy, config(basicCost = 5.0)) { _, _, _, receipt ->
            assertNotNull(receipt)
            assertTrue(receipt!!.beginRefund())
            assertTrue(economy.deposit(receipt!!.payerId, receipt!!.amount))
            receipt!!.completeRefund(true)
            false
        }.start(request())

        assertEquals(StartFailure.START_FAILED, (result as StartResult.Rejected).failure)
        assertEquals(1, economy.deposits)
        assertEquals(20.0, economy.currentBalance)
    }

    @Test
    fun `failed refund is reported to operator log`() {
        val economy = FakeEconomy(20.0, depositSucceeds = false)
        val errors = mutableListOf<String>()
        val result = service(economy, config(basicCost = 5.0), logger = { message, _ -> errors += message }) { _, _, _, _ -> false }
            .start(request())

        assertEquals(StartFailure.REFUND_FAILED, (result as StartResult.Rejected).failure)
        assertEquals(15.0, economy.currentBalance)
        assertTrue(errors.any { it.contains("refund failed") })
    }

    @Test
    fun `admin and console starts are free`() {
        listOf(
            StartRequest(StartActor(playerId, isAdmin = true), arena, StartSource.ADMIN_COMMAND),
            StartRequest(StartActor(null, isConsole = true), arena, StartSource.CONSOLE),
        ).forEach { adminRequest ->
            val economy = FakeEconomy(20.0)
            var payment: PaymentReceipt? = PaymentReceipt(playerId, 1.0, StartSource.PLAYER_COMMAND)
            val result = service(economy, config(basicCost = 10.0)) { _, kind, _, observed ->
                assertEquals(EventKind.ADMIN, kind)
                payment = observed
                true
            }.start(adminRequest)

            assertInstanceOf(StartResult.Started::class.java, result)
            assertEquals(null, payment)
            assertEquals(0, economy.withdrawals)
        }
    }

    @Test
    fun `concurrent duplicate attempts cannot double charge or start`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val economy = FakeEconomy(20.0)
        val service = service(economy, config(basicCost = 5.0)) { _, _, _, _ ->
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
        advancedCost: Double = basicCost + 1.0,
        delay: Int = 0,
        lock: LockState = LockState.UNLOCKED,
        enabled: Boolean = true,
    ) = EnthusiaKothConfig(
        manualStart = ManualStartConfig(
            enabled = enabled,
            basicCost = basicCost,
            advancedCost = advancedCost,
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
        private val balanceThrows: Boolean = false,
        private val withdrawThrows: Boolean = false,
    ) : PlayerEconomy {
        var withdrawals = 0
        var deposits = 0

        override fun isAvailable() = true

        override fun balance(playerId: UUID): Double {
            if (balanceThrows) error("balance boom")
            return currentBalance
        }

        override fun withdraw(playerId: UUID, amount: Double): Boolean {
            if (withdrawThrows) error("withdraw boom")
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
