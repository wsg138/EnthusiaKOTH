package net.badgersmc.ek.infrastructure.display

import net.badgersmc.ek.domain.CaptureZone
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Draws the 2D KOTH zone border on the ground using BlockDisplay entities.
 * Four paper-thin edge-spanning displays form a glowing wireframe rectangle
 * (like WorldEdit CUI cuboid render). Visible through walls.
 * Managed per-event — automatically cleaned up on KOTH end.
 */
class ZoneBorderService(private val plugin: JavaPlugin) {

    companion object {
        const val BORDER_TAG = "ekoth_zone_border"
    }

    private val activeBorders = mutableListOf<BlockDisplay>()

    /** Show the border for a zone. Clears any previous border first. */
    fun show(zone: CaptureZone) {
        hide()
        val world = plugin.server.getWorld(zone.worldName) ?: return
        val y = zone.objectiveY + 0.1 // just above the configured objective surface

        // Sweep leftover border entities from a previous unclean shutdown
        world.entities.filter { it.scoreboardTags.contains(BORDER_TAG) }.forEach { it.remove() }

        // Four edges: north, east, south (reversed), west (reversed)
        spawnEdge(world, zone.minX, y, zone.minZ, zone.maxX, zone.minZ)
        spawnEdge(world, zone.maxX, y, zone.minZ, zone.maxX, zone.maxZ)
        spawnEdge(world, zone.maxX, y, zone.maxZ, zone.minX, zone.maxZ)
        spawnEdge(world, zone.minX, y, zone.maxZ, zone.minX, zone.minZ)
    }

    /** Remove the border. Safe to call multiple times. */
    fun hide() {
        activeBorders.forEach { it.remove() }
        activeBorders.clear()
    }

    /**
     * Spawns a single BlockDisplay entity that spans from (x1,z1) to (x2,z2).
     * The display is scaled paper-thin (0.005) so it renders as a glowing wireframe line
     * — identical to the WorldEdit CUI cuboid render aesthetic.
     */
    private fun spawnEdge(world: org.bukkit.World, x1: Double, y: Double, z1: Double, x2: Double, z2: Double) {
        val midX = (x1 + x2) / 2.0
        val midZ = (z1 + z2) / 2.0
        val loc = Location(world, midX, y, midZ)

        val dx = x2 - x1
        val dz = z2 - z1
        val length = kotlin.math.sqrt(dx * dx + dz * dz)
        if (length < 0.5) return

        val yaw = kotlin.math.atan2(dz, dx).toFloat()

        val display = world.spawn(loc, BlockDisplay::class.java) { d ->
            d.block = Bukkit.createBlockData(Material.RED_STAINED_GLASS)
            d.setGlowing(true)
            d.setGlowColorOverride(Color.RED)
            d.setViewRange(64f)
            d.setShadowStrength(0.0f)
            d.setBrightness(Display.Brightness(15, 15))
            // Don't persist border entities with chunks — unclean shutdowns
            // would otherwise leave permanent glowing blocks in the world.
            d.isPersistent = false
            d.addScoreboardTag(BORDER_TAG)
        }

        // Transformation = Translation * LeftRotation * Scale * RightRotation.
        // The yaw must be the LEFT rotation so it is applied AFTER the non-uniform
        // scale (length × 0.005 × 0.005); as the right rotation it would rotate the
        // block first and then flatten it to the local X axis, distorting the edge.
        // The block display model is centered on the entity origin, so the scaled
        // edge is already centered at the edge midpoint — no translation offset needed.
        display.transformation = org.bukkit.util.Transformation(
            Vector3f(0f, 0f, 0f),
            Quaternionf().rotateY(yaw),
            Vector3f(length.toFloat(), 0.005f, 0.005f),  // paper-thin line
            Quaternionf(),
        )

        display.setRotation(0f, 0f)
        activeBorders.add(display)
    }

    /** Flash the border briefly when a KOTH starts (visual pulse). */
    fun flashStart(zone: CaptureZone) {
        show(zone)
        object : BukkitRunnable() {
            override fun run() {
                hide()
                this.cancel()
            }
        }.runTaskLater(plugin, 10L)
    }
}
