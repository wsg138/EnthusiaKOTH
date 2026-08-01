package net.badgersmc.ek

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Legacy color-code stripping — used for names before they're substituted
 * into console reward commands.
 */
class StripColorsTest {

    @Test
    fun `strips legacy section codes`() {
        assertEquals("Hello", "§aHello".stripColors())
        assertEquals("Hello", "&cHello".stripColors())
        assertEquals("Hello", "§l§oHello".stripColors())
    }

    @Test
    fun `strips legacy RGB section sequences`() {
        // §x§R§R§G§G§B§B — the RGB prefix that the old regex missed
        assertEquals("Hello", "§x§F§F§C§8§0§0Hello".stripColors())
        assertEquals("Hello", "&x&F&F&C&8&0&0Hello".stripColors())
    }

    @Test
    fun `strips minimessage tags`() {
        assertEquals("Hello", "<red>Hello".stripColors())
        assertEquals("Hello", "<gradient:#FF0000:#00FF00>Hello</gradient>".stripColors())
    }

    @Test
    fun `leaves plain text untouched`() {
        assertEquals("BadgersMC_01", "BadgersMC_01".stripColors())
    }
}

/**
 * Lock-state gating — MANUAL_LOCKED must block manually-started KOTHs
 * (commands, flares) but let scheduled rotations and private tests through.
 */
class LockStateTest {

    @Test
    fun `unlocked allows everything`() {
        assertTrue(net.badgersmc.ek.domain.LockState.UNLOCKED.allows(net.badgersmc.ek.domain.EventKind.STANDARD))
        assertTrue(net.badgersmc.ek.domain.LockState.UNLOCKED.allows(net.badgersmc.ek.domain.EventKind.SCHEDULED))
        assertTrue(net.badgersmc.ek.domain.LockState.UNLOCKED.allows(net.badgersmc.ek.domain.EventKind.PRIVATE_TEST))
    }

    @Test
    fun `manual locked blocks manual starts but not scheduled or private`() {
        assertFalse(net.badgersmc.ek.domain.LockState.MANUAL_LOCKED.allows(net.badgersmc.ek.domain.EventKind.STANDARD))
        assertTrue(net.badgersmc.ek.domain.LockState.MANUAL_LOCKED.allows(net.badgersmc.ek.domain.EventKind.SCHEDULED))
        assertTrue(net.badgersmc.ek.domain.LockState.MANUAL_LOCKED.allows(net.badgersmc.ek.domain.EventKind.PRIVATE_TEST))
    }

    @Test
    fun `all locked blocks everything`() {
        assertFalse(net.badgersmc.ek.domain.LockState.ALL_LOCKED.allows(net.badgersmc.ek.domain.EventKind.STANDARD))
        assertFalse(net.badgersmc.ek.domain.LockState.ALL_LOCKED.allows(net.badgersmc.ek.domain.EventKind.SCHEDULED))
        assertFalse(net.badgersmc.ek.domain.LockState.ALL_LOCKED.allows(net.badgersmc.ek.domain.EventKind.PRIVATE_TEST))
    }
}
