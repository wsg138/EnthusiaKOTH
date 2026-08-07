package net.badgersmc.ek.application

import io.mockk.mockk
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.TeamMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class StartTeamModeTest {
    @Test
    fun `selected guild mode reaches production event starter`() {
        var observed: TeamMode? = null
        val starter = object : EventStarter {
            override fun start(
                arena: KothArena,
                kind: EventKind,
                delaySeconds: Int,
                payment: PaymentReceipt?,
            ): Boolean = error("mode-aware overload must be used")

            override fun start(
                arena: KothArena,
                kind: EventKind,
                delaySeconds: Int,
                payment: PaymentReceipt?,
                teamMode: TeamMode,
            ): Boolean {
                observed = teamMode
                return true
            }
        }
        val service = StartService(
            config = { EnthusiaKothConfig() },
            pluginReady = { true },
            hasConflictingEvent = { false },
            economy = mockk(relaxed = true),
            starter = starter,
            logError = { _, _ -> },
        )

        val result = service.start(
            StartRequest(
                actor = StartActor(UUID.randomUUID(), isAdmin = true),
                arena = KothArena(
                    id = "capture",
                    family = "capture",
                    zone = mockk<CaptureZone>(relaxed = true),
                    durationSeconds = 60,
                    captureSeconds = 10,
                ),
                source = StartSource.ADMIN_COMMAND,
                teamMode = TeamMode.GUILD,
            ),
        )

        assertInstanceOf(StartResult.Started::class.java, result)
        assertEquals(TeamMode.GUILD, observed)
    }
}
