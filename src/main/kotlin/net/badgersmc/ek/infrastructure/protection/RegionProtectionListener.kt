package net.badgersmc.ek.infrastructure.protection

import net.badgersmc.ek.toComponent
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent

/**
 * Enforces permanent terrain protection for all KOTH arena zones.
 * Protects against block modification, explosions, pistons, and bucket usage.
 */
class RegionProtectionListener(
    private val protection: RegionProtectionService,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) : Listener {

    private val protectedMsg by lazy { lang.msg("protection.protected") }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (protection.isProtected(event.block.location)) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (protection.isProtected(event.blockPlaced.location)) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (isProtectedBucketTarget(event.blockClicked, event.blockFace)) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (protection.isProtected(event.blockClicked.location)) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { block ->
            protection.isProtected(block.location)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { block ->
            protection.isProtected(block.location)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (crossesProtectedBoundary(event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (crossesProtectedBoundary(event.blocks, event.direction)) {
            event.isCancelled = true
        }
    }

    /**
     * Checks if the block that would be placed by a bucket is in a protected zone.
     * The bucket places into the block adjacent to the clicked face.
     */
    private fun isProtectedBucketTarget(clicked: Block, face: BlockFace): Boolean {
        return protection.isProtected(clicked.getRelative(face).location)
    }

    /**
     * Checks if any block in the piston's move list, or the block it would be pushed into,
     * is in a protected zone.
     */
    private fun crossesProtectedBoundary(blocks: List<Block>, direction: BlockFace): Boolean {
        return blocks.any { block ->
            protection.isProtected(block.location) ||
                    protection.isProtected(block.getRelative(direction).location)
        }
    }
}