package net.badgersmc.ek.application

import io.mockk.mockk
import net.badgersmc.ek.domain.CaptureLeaveBehavior
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventState
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CaptureTakeoverTest {
    private val teamA = TeamId(TeamMode.GUILD, UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val teamB = TeamId(TeamMode.GUILD, UUID.fromString("00000000-0000-0000-0000-00000000000b"))

    @Test
    fun `decay releases prior controller and lets another team take control immediately`() {
        val event = event(CaptureLeaveBehavior.DECAY, decay = 2.0)
        event.currentController = teamA
        event.scores[teamA] = 7.0

        val step = KothService.applyCaptureControl(event, listOf(teamB))

        assertEquals(teamA, step.left)
        assertEquals(teamB, step.entered)
        assertFalse(step.progressCurrent)
        assertEquals(teamB, event.currentController)
        assertEquals(5.0, event.scores[teamA])
    }

    @Test
    fun `reset still clears stored progress while allowing immediate takeover`() {
        val event = event(CaptureLeaveBehavior.RESET)
        event.currentController = teamA
        event.scores[teamA] = 7.0
        event.scores[teamB] = 2.0

        KothService.applyCaptureControl(event, listOf(teamB))

        assertEquals(teamB, event.currentController)
        assertFalse(event.scores.containsKey(teamA))
        assertEquals(2.0, event.scores[teamB])
    }

    @Test
    fun `pause preserves prior progress while allowing immediate takeover`() {
        val event = event(CaptureLeaveBehavior.PAUSE)
        event.currentController = teamA
        event.scores[teamA] = 7.0

        KothService.applyCaptureControl(event, listOf(teamB))

        assertEquals(teamB, event.currentController)
        assertEquals(7.0, event.scores[teamA])
    }

    @Test
    fun `contested point releases controller without choosing between multiple teams`() {
        val event = event(CaptureLeaveBehavior.DECAY, decay = 1.5)
        event.currentController = teamA
        event.scores[teamA] = 7.0

        val step = KothService.applyCaptureControl(event, listOf(teamA, teamB))

        assertEquals(teamA, step.left)
        assertNull(step.entered)
        assertNull(event.currentController)
        assertEquals(5.5, event.scores[teamA])
    }

    @Test
    fun `decayed team can reenter without its saved progress blocking other control`() {
        val event = event(CaptureLeaveBehavior.DECAY, decay = 2.0)
        event.currentController = teamA
        event.scores[teamA] = 7.0

        KothService.applyCaptureControl(event, listOf(teamB))
        KothService.applyCaptureControl(event, listOf(teamA))

        assertEquals(teamA, event.currentController)
        assertEquals(5.0, event.scores[teamA])
    }

    private fun event(leaveBehavior: CaptureLeaveBehavior, decay: Double = 1.0) = KothEvent(
        id = UUID.randomUUID(),
        arena = KothArena(
            id = "capture",
            family = "capture",
            zone = mockk<CaptureZone>(relaxed = true),
            durationSeconds = 60,
            captureSeconds = 10,
            leaveBehavior = leaveBehavior,
            decayPerSecond = decay,
            contestWhenMultipleCappers = true,
        ),
        startsAt = Instant.parse("2026-08-06T12:00:00Z"),
        endsAt = Instant.parse("2026-08-06T12:01:00Z"),
        state = EventState.ACTIVE,
        teamMode = TeamMode.GUILD,
    )
}
