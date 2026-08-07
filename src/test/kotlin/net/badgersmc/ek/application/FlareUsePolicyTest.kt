package net.badgersmc.ek.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FlareUsePolicyTest {
    @Test
    fun `disabled locked active and failed starts retain the flare`() {
        assertEquals("flare.disabled", FlareUsePolicy.rejectionKey(StartResult.Rejected(StartFailure.FEATURE_DISABLED)))
        assertEquals("flare.locked", FlareUsePolicy.rejectionKey(StartResult.Rejected(StartFailure.LOCKED)))
        assertEquals("flare.already_active", FlareUsePolicy.rejectionKey(StartResult.Rejected(StartFailure.ALREADY_ACTIVE)))
        assertEquals("flare.failed", FlareUsePolicy.rejectionKey(StartResult.Rejected(StartFailure.START_FAILED)))
    }

    @Test
    fun `permission rejection uses the shared permission message`() {
        assertEquals("command.error.no_permission", FlareUsePolicy.rejectionKey(StartResult.Rejected(StartFailure.NO_PERMISSION)))
    }

    @Test
    fun `main hand stack decrements without clearing while items remain`() {
        val result = FlareUsePolicy.consumption(3, FlareHand.MAIN)
        assertEquals(2, result.newAmount)
        assertNull(result.clearHand)
    }

    @Test
    fun `single main hand flare clears main hand only`() {
        assertEquals(FlareConsumption(0, FlareHand.MAIN), FlareUsePolicy.consumption(1, FlareHand.MAIN))
    }

    @Test
    fun `single offhand flare clears offhand only`() {
        assertEquals(FlareConsumption(0, FlareHand.OFF), FlareUsePolicy.consumption(1, FlareHand.OFF))
    }
}
