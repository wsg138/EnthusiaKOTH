package net.badgersmc.ek.domain

import org.bukkit.Location
import org.bukkit.World

/**
 * A cuboid capture zone for KOTH.
 */
data class CaptureZone(
    val id: String,
    val worldName: String,
    val corner1: Location,
    val corner2: Location,
    val radius: Double = 5.0, // used by MOVING family — radius around moving point
) {
    val minX: Double get() = minOf(corner1.x, corner2.x)
    val maxX: Double get() = maxOf(corner1.x, corner2.x)
    val minY: Double get() = minOf(corner1.y, corner2.y)
    val maxY: Double get() = maxOf(corner1.y, corner2.y)
    val minZ: Double get() = minOf(corner1.z, corner2.z)
    val maxZ: Double get() = maxOf(corner1.z, corner2.z)
    val radiusSq: Double get() = radius * radius

    fun contains(loc: Location): Boolean {
        if (loc.world?.name != worldName) return false
        return loc.x in minX..maxX
                && loc.y in minOf(corner1.y, corner2.y)..maxOf(corner1.y, corner2.y)
                && loc.z in minZ..maxZ
    }

    fun verticalBounds(): Pair<Double, Double> =
        minOf(corner1.y, corner2.y) to maxOf(corner1.y, corner2.y)

    fun center(world: World): Location {
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val cz = (minZ + maxZ) / 2.0
        return Location(world, cx, cy, cz)
    }
}
