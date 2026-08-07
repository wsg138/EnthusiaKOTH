package net.badgersmc.ek.application

import io.mockk.mockk
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventState
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.domain.PrivateJoinResult
import net.badgersmc.ek.domain.PrivateTestAccess
import net.badgersmc.ek.domain.TeamMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KothEventModeTest {
    @Test
    fun `solo event keeps guild members independent`() {
        val player = UUID.randomUUID()
        val guild = UUID.randomUUID()
        val event = event(teamMode = TeamMode.SOLO)

        val team = event.resolveTeam(player, guild)

        assertEquals(TeamMode.SOLO, team?.mode)
        assertEquals(player, team?.id)
    }

    @Test
    fun `guild event groups guild members and excludes guildless players`() {
        val player = UUID.randomUUID()
        val guild = UUID.randomUUID()
        val event = event(teamMode = TeamMode.GUILD)

        assertEquals(guild, event.resolveTeam(player, guild)?.id)
        assertEquals(TeamMode.GUILD, event.resolveTeam(player, guild)?.mode)
        assertNull(event.resolveTeam(UUID.randomUUID(), null))
    }

    @Test
    fun `owner-only private test cannot be joined`() {
        val owner = UUID.randomUUID()
        val event = event(
            teamMode = TeamMode.SOLO,
            owner = owner,
            privateTestAccess = PrivateTestAccess.OWNER_ONLY,
        )
        event.join(owner)

        assertEquals(PrivateJoinResult.OWNER_ONLY, event.joinPrivate(UUID.randomUUID()))
        assertEquals(1, event.participants.size)
    }

    @Test
    fun `staff private test accepts authorized command path exactly once`() {
        val owner = UUID.randomUUID()
        val staff = UUID.randomUUID()
        val event = event(
            teamMode = TeamMode.GUILD,
            owner = owner,
            privateTestAccess = PrivateTestAccess.PERMISSION_JOIN,
        )
        event.join(owner)

        assertEquals(PrivateJoinResult.JOINED, event.joinPrivate(staff))
        assertEquals(PrivateJoinResult.ALREADY_JOINED, event.joinPrivate(staff))
    }

    private fun event(
        teamMode: TeamMode,
        owner: UUID? = null,
        privateTestAccess: PrivateTestAccess? = null,
    ) = KothEvent(
        id = UUID.randomUUID(),
        arena = KothArena(
            id = "capture",
            family = "capture",
            zone = mockk<CaptureZone>(relaxed = true),
            durationSeconds = 60,
            captureSeconds = 10,
        ),
        startsAt = Instant.parse("2026-08-06T12:00:00Z"),
        endsAt = Instant.parse("2026-08-06T12:01:00Z"),
        state = EventState.ACTIVE,
        teamMode = teamMode,
        owner = owner,
        isPrivateTest = owner != null,
        privateTestAccess = privateTestAccess,
    )
}
