package net.badgersmc.ek.application

import net.badgersmc.ek.domain.CaptureZone
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.entity.Firework
import org.bukkit.inventory.meta.FireworkMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import kotlin.random.Random

/**
 * Celebration firework display on KOTH capture.
 * Fires 4 fireworks sequentially from the zone's 4 corners.
 * Random colors, shapes, trails, and flicker every time.
 */
class FireworkCelebrationService(private val plugin: JavaPlugin) {

    companion object {
        const val EKOTH_FIREWORK_TAG = "ekoth.celebration"
        private val COLORS = arrayOf(
            Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN,
            Color.BLUE, Color.PURPLE, Color.AQUA, Color.FUCHSIA,
            Color.LIME, Color.MAROON, Color.NAVY, Color.OLIVE,
            Color.SILVER, Color.TEAL, Color.WHITE,
        )
        private val SHAPES = FireworkEffect.Type.entries.toTypedArray()
    }

    /**
     * Launches 4 fireworks sequentially (one every 10 ticks) from the zone corners.
     */
    fun celebrate(zone: CaptureZone) {
        val world = plugin.server.getWorld(zone.worldName) ?: return
        val corners = zoneCorners(zone, world.spawnLocation)

        for ((index, corner) in corners.withIndex()) {
            object : BukkitRunnable() {
                override fun run() {
                    launchFirework(corner)
                }
            }.runTaskLater(plugin, (index * 10L))
        }
    }

    private fun launchFirework(loc: Location) {
        val world = loc.world ?: return
        val firework = world.spawn(loc, Firework::class.java)
        val meta = firework.fireworkMeta

        val effect = buildRandomEffect()
        meta.addEffect(effect)
        meta.power = 1

        firework.fireworkMeta = meta
        firework.addScoreboardTag(EKOTH_FIREWORK_TAG)
        firework.setTicksToDetonate(2)
    }

    private fun buildRandomEffect(): FireworkEffect {
        val color = COLORS.random()
        val fadeColor = COLORS.random()
        val shape = SHAPES.random()
        val trail = Random.nextBoolean()
        val flicker = Random.nextBoolean()

        return FireworkEffect.builder()
            .withColor(color)
            .withFade(fadeColor)
            .with(shape)
            .trail(trail)
            .flicker(flicker)
            .build()
    }

    private fun zoneCorners(zone: CaptureZone, defaultLoc: Location): List<Location> {
        val world = plugin.server.getWorld(zone.worldName) ?: return cornersFrom(defaultLoc)
        val (minY, maxY) = zone.verticalBounds()
        val launchY = minY.coerceAtLeast(world.minHeight.toDouble()) + 3.0

        return listOf(
            Location(world, zone.minX, launchY, zone.minZ),
            Location(world, zone.maxX, launchY, zone.minZ),
            Location(world, zone.maxX, launchY, zone.maxZ),
            Location(world, zone.minX, launchY, zone.maxZ),
        )
    }

    private fun cornersFrom(loc: Location): List<Location> {
        val w = loc.world ?: return emptyList()
        val y = loc.y + 3.0
        return listOf(
            Location(w, loc.x - 5, y, loc.z - 5),
            Location(w, loc.x + 5, y, loc.z - 5),
            Location(w, loc.x + 5, y, loc.z + 5),
            Location(w, loc.x - 5, y, loc.z + 5),
        )
    }
}
