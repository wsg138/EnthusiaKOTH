package net.badgersmc.ek.application

import io.mockk.mockk
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Tests for the MOVING-family path calculation.
 * The old implementation double-counted the half-size on the first segment,
 * which put the point off the square path (overshoot) and caused
 * teleport-like jumps when the distance wrapped around the perimeter.
 */
class MovingPathTest {

    private val size = 20.0
    private val speed = 1.0
    private val cx = 100.0
    private val cz = 200.0
    private val half = size / 2.0

    @Test
    fun `moving control accumulates score and never uses capture threshold`() {
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = KothArena(
                id = "moving",
                family = "moving",
                zone = mockk(relaxed = true),
                durationSeconds = 300,
                captureSeconds = 2,
            ),
            startsAt = Instant.EPOCH,
            endsAt = Instant.EPOCH.plusSeconds(300),
        )
        val team = TeamId(TeamMode.SOLO, UUID.randomUUID())

        repeat(5) { KothService.applyMovingScore(event, listOf(team)) }

        assertEquals(5.0, event.scores[team])
        assertEquals(team, event.currentController)
        assertEquals(0.5f, KothService.displayProgress(event, Instant.EPOCH.plusSeconds(150)))
        KothService.applyMovingScore(event, listOf(team, TeamId(TeamMode.SOLO, UUID.randomUUID())))
        assertNull(event.currentController)
        assertEquals(5.0, event.scores[team])
    }

    @Test
    fun `moving capture requires vertical bounds and horizontal radius`() {
        val zone = CaptureZone(
            id = "moving-zone",
            worldName = "world",
            corner1 = Location(null, -10.0, 64.0, -10.0),
            corner2 = Location(null, 10.0, 72.0, 10.0),
            radius = 5.0,
        )

        assertTrue(KothService.isMovingCaptureEligible(zone, 3.0, 68.0, 4.0, 0.0, 0.0))
        assertTrue(KothService.isMovingCaptureEligible(zone, 5.0, 64.0, 0.0, 0.0, 0.0))
        assertFalse(KothService.isMovingCaptureEligible(zone, 0.0, 63.99, 0.0, 0.0, 0.0))
        assertFalse(KothService.isMovingCaptureEligible(zone, 0.0, 72.01, 0.0, 0.0, 0.0))
        assertFalse(KothService.isMovingCaptureEligible(zone, 5.01, 68.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun `point stays within the square bounds at all times`() {
        // Sample a full lap at 0.5s resolution — the point must never leave
        // the [cx-half, cx+half] x [cz-half, cz+half] square.
        var t = 0.0
        while (t < 80.0) { // 4*size seconds at 1 block/s = one full lap
            val (px, pz) = KothService.movingPointAt(t, size, speed, cx, cz)
            assertTrue(px >= cx - half - 1e-9 && px <= cx + half + 1e-9, "x out of bounds at t=$t: $px")
            assertTrue(pz >= cz - half - 1e-9 && pz <= cz + half + 1e-9, "z out of bounds at t=$t: $pz")
            t += 0.5
        }
    }

    @Test
    fun `point does not jump between consecutive seconds`() {
        // The capture point must move at most `speed` blocks per second.
        // The old math teleported the point across the square when segments
        // didn't line up; a fixed path can never move faster than speed.
        var prev = KothService.movingPointAt(0.0, size, speed, cx, cz)
        var t = 1.0
        while (t <= 80.0) {
            val next = KothService.movingPointAt(t, size, speed, cx, cz)
            val dx = next.first - prev.first
            val dz = next.second - prev.second
            val moved = abs(dx) + abs(dz)
            assertTrue(moved <= speed + 1e-9, "jump of $moved blocks between t=${t - 1} and t=$t")
            prev = next
            t += 1.0
        }
    }

    @Test
    fun `passes through all four corners of the square`() {
        val topLeft = KothService.movingPointAt(0.0, size, speed, cx, cz)
        assertEquals(cx - half, topLeft.first, 1e-9)
        assertEquals(cz - half, topLeft.second, 1e-9)

        val topRight = KothService.movingPointAt(size, size, speed, cx, cz)
        assertEquals(cx + half, topRight.first, 1e-9)
        assertEquals(cz - half, topRight.second, 1e-9)

        val bottomRight = KothService.movingPointAt(size * 2, size, speed, cx, cz)
        assertEquals(cx + half, bottomRight.first, 1e-9)
        assertEquals(cz + half, bottomRight.second, 1e-9)

        val bottomLeft = KothService.movingPointAt(size * 3, size, speed, cx, cz)
        assertEquals(cx - half, bottomLeft.first, 1e-9)
        assertEquals(cz + half, bottomLeft.second, 1e-9)

        // A full lap returns to the start
        val lapEnd = KothService.movingPointAt(size * 4, size, speed, cx, cz)
        assertEquals(topLeft.first, lapEnd.first, 1e-9)
        assertEquals(topLeft.second, lapEnd.second, 1e-9)
    }

    @Test
    fun `path wraps around the perimeter without jumping`() {
        // t=79 and t=80 are on opposite sides of the wrap point (t=80 = one lap)
        val nearEnd = KothService.movingPointAt(79.0, size, speed, cx, cz)
        val wrap = KothService.movingPointAt(80.0, size, speed, cx, cz)
        assertEquals(cx - half, wrap.first, 1e-9)
        assertEquals(cz - half, wrap.second, 1e-9)
        // Just before the wrap the point is on the left edge, 19 blocks up
        // from the bottom-left corner (distance 60..80 = left edge, bottom→top)
        assertEquals(cx - half, nearEnd.first, 1e-9)
        assertEquals(cz + half - 19.0, nearEnd.second, 1e-9)
    }

    @Test
    fun `negative elapsed time clamps to the start of the path`() {
        val (px, pz) = KothService.movingPointAt(-5.0, size, speed, cx, cz)
        assertEquals(cx - half, px, 1e-9)
        assertEquals(cz - half, pz, 1e-9)
    }

    @Test
    fun `faster speed travels further in the same time`() {
        val slow = KothService.movingPointAt(10.0, size, 1.0, cx, cz)
        val fast = KothService.movingPointAt(10.0, size, 2.0, cx, cz)
        // At t=10: slow is 10 blocks along, fast is 20 blocks (top-right corner)
        assertEquals(cx - half + 10.0, slow.first, 1e-9)
        assertEquals(cx + half, fast.first, 1e-9)
        assertEquals(cz - half, fast.second, 1e-9)
    }
}
