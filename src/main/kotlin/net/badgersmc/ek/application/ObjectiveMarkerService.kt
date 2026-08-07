package net.badgersmc.ek.application

import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal data class ObjectiveMarkerPoint(val x: Double, val y: Double, val z: Double)

class ObjectiveMarkerService {
    fun show(event: KothEvent, audience: Collection<Player>, showStaticObjective: Boolean) {
        val moving = event.arena.family.equals("moving", ignoreCase = true)
        if (!moving && !showStaticObjective) return
        val world = Bukkit.getWorld(event.arena.zone.worldName) ?: return
        val points = if (moving) {
            val point = event.movingPoint ?: return
            val surfaceY = world.getHighestBlockYAt(floor(point.first).toInt(), floor(point.third).toInt()) + 1.2
            movingRingPoints(point.first, surfaceY, point.third, event.arena.zone.radius)
        } else {
            staticZonePoints(event.arena.zone)
        }
        audience.asSequence()
            .filter { it.isOnline && it.world.uid == world.uid }
            .forEach { player ->
                points.forEach { point ->
                    player.spawnParticle(
                        Particle.END_ROD,
                        Location(world, point.x, point.y, point.z),
                        2,
                        0.08,
                        0.18,
                        0.08,
                        0.0,
                    )
                }
            }
    }

    companion object {
        internal fun staticZonePoints(zone: CaptureZone): List<ObjectiveMarkerPoint> {
            val y = zone.objectiveY + 0.35
            val centerX = (zone.minX + zone.maxX) / 2.0
            val centerZ = (zone.minZ + zone.maxZ) / 2.0
            return listOf(
                ObjectiveMarkerPoint(zone.minX, y, zone.minZ),
                ObjectiveMarkerPoint(zone.minX, y, zone.maxZ),
                ObjectiveMarkerPoint(zone.maxX, y, zone.minZ),
                ObjectiveMarkerPoint(zone.maxX, y, zone.maxZ),
                ObjectiveMarkerPoint(centerX, y, centerZ),
            )
        }

        internal fun movingRingPoints(
            centerX: Double,
            y: Double,
            centerZ: Double,
            radius: Double,
            segments: Int = 12,
        ): List<ObjectiveMarkerPoint> {
            val safeSegments = segments.coerceAtLeast(4)
            val safeRadius = radius.coerceAtLeast(0.5)
            return (0 until safeSegments).map { index ->
                val angle = (2.0 * PI * index) / safeSegments
                ObjectiveMarkerPoint(
                    centerX + cos(angle) * safeRadius,
                    y,
                    centerZ + sin(angle) * safeRadius,
                )
            }
        }
    }
}
