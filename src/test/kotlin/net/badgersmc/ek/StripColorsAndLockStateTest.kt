package net.badgersmc.ek

import io.mockk.mockk
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.EventState
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.domain.LockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StripColorsTest {
    @Test
    fun `strips legacy section codes`() {
        assertEquals("Hello", "§aHello".stripColors())
        assertEquals("Hello", "&cHello".stripColors())
        assertEquals("Hello", "§l§oHello".stripColors())
    }

    @Test
    fun `strips legacy RGB section sequences`() {
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

class LockStateTest {
    @Test
    fun `unlocked allows every start source`() {
        EventKind.entries.forEach { assertTrue(LockState.UNLOCKED.allows(it), it.name) }
    }

    @Test
    fun `manual lock blocks player command gui and flare only`() {
        assertFalse(LockState.MANUAL_LOCKED.allows(EventKind.PLAYER_COMMAND))
        assertFalse(LockState.MANUAL_LOCKED.allows(EventKind.GUI))
        assertFalse(LockState.MANUAL_LOCKED.allows(EventKind.FLARE))
        assertTrue(LockState.MANUAL_LOCKED.allows(EventKind.ADMIN))
        assertTrue(LockState.MANUAL_LOCKED.allows(EventKind.SCHEDULED))
        assertTrue(LockState.MANUAL_LOCKED.allows(EventKind.PRIVATE_TEST))
    }

    @Test
    fun `all lock blocks every start source`() {
        EventKind.entries.forEach { assertFalse(LockState.ALL_LOCKED.allows(it), it.name) }
    }
}

class PrivateParticipationTest {
    @Test
    fun `private event admits owner and joined participants only`() {
        val owner = UUID.randomUUID()
        val joined = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = mockk<KothArena>(),
            startsAt = now,
            endsAt = now.plusSeconds(60),
            state = EventState.ACTIVE,
            owner = owner,
            isPrivateTest = true,
        )
        event.join(joined)

        assertTrue(event.isParticipant(owner))
        assertTrue(event.isParticipant(joined))
        assertFalse(event.isParticipant(outsider))
    }

    @Test
    fun `public event admits all players`() {
        val now = Instant.parse("2026-08-06T12:00:00Z")
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = mockk<KothArena>(),
            startsAt = now,
            endsAt = now.plusSeconds(60),
            state = EventState.ACTIVE,
        )
        assertTrue(event.isParticipant(UUID.randomUUID()))
    }
}
