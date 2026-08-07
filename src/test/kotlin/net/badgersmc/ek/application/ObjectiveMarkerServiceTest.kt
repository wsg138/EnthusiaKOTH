package net.badgersmc.ek.application

import io.mockk.mockk
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import org.bukkit.Location
import org.bukkit.World
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.math.hypot

class ObjectiveMarkerServiceTest {
    @Test
    fun `static objective markers include four corners and center`() {
        val world = mockk<World>()
        val zone = CaptureZone(
            "capture",
            "world",
            Location(world, 2.0, 64.0, 4.0),
            Location(world, 10.0, 70.0, 12.0),
        )

        val points = ObjectiveMarkerService.staticZonePoints(zone)

        assertEquals(5, points.size)
        assertTrue(ObjectiveMarkerPoint(6.0, 67.35, 8.0) in points)
    }

    @Test
    fun `moving objective marker forms a ring at configured radius`() {
        val points = ObjectiveMarkerService.movingRingPoints(10.0, 65.0, -4.0, radius = 5.0, segments = 12)

        assertEquals(12, points.size)
        points.forEach { point ->
            assertEquals(5.0, hypot(point.x - 10.0, point.z + 4.0), 0.000001)
            assertEquals(65.0, point.y)
        }
    }

    @Test
    fun `bossbar progress follows current controller score and clamps`() {
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = KothArena(
                id = "capture",
                family = "capture",
                zone = mockk(relaxed = true),
                durationSeconds = 120,
                captureSeconds = 20,
            ),
            startsAt = Instant.EPOCH,
            endsAt = Instant.EPOCH.plusSeconds(120),
        )
        val controller = TeamId(TeamMode.SOLO, UUID.randomUUID())
        event.currentController = controller
        event.scores[controller] = 5.0

        assertEquals(0.25f, KothService.displayProgress(event, Instant.EPOCH))
        event.scores[controller] = 50.0
        assertEquals(1.0f, KothService.displayProgress(event, Instant.EPOCH))
    }

    @Test
    fun `conquest bossbar progress represents remaining event time`() {
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = KothArena(
                id = "conquest",
                family = "conquest",
                zone = mockk(relaxed = true),
                durationSeconds = 120,
                captureSeconds = 20,
            ),
            startsAt = Instant.EPOCH,
            endsAt = Instant.EPOCH.plusSeconds(120),
        )

        assertEquals(0.5f, KothService.displayProgress(event, Instant.EPOCH.plusSeconds(60)))
        assertEquals(0.0f, KothService.displayProgress(event, Instant.EPOCH.plusSeconds(180)))
    }
}
