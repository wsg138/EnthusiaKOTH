package net.badgersmc.ek.infrastructure.protection

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Display
import org.bukkit.entity.Hanging
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent

/**
 * Permanent arena protection.
 *
 * The bypass is intentionally limited to direct player maintenance. Automated destruction,
 * fluids, fire, pistons, and explosions remain protected even when an operator is nearby.
 */
class RegionProtectionListener(
    private val protection: RegionProtectionService,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) : Listener {

    private val protectedMsg by lazy { lang.msg("protection.protected") }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (mayBypass(event.player)) return
        if (connectedBlocks(event.block).any { protection.isProtected(it.location) }) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (mayBypass(event.player)) return
        if (protection.isProtected(event.blockPlaced.location)) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMultiPlace(event: BlockMultiPlaceEvent) {
        if (mayBypass(event.player)) return
        val anyProtected = protection.isProtected(event.blockPlaced.location) ||
            event.replacedBlockStates.any { protection.isProtected(it.location) }
        if (anyProtected) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (mayBypass(event.player)) return
        if (isProtectedBucketTarget(event.blockClicked, event.blockFace)) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (mayBypass(event.player)) return
        if (protection.isProtected(event.blockClicked.location)) {
            event.isCancelled = true
            event.player.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { protection.isProtected(it.location) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { protection.isProtected(it.location) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChangeBlock(event: org.bukkit.event.entity.EntityChangeBlockEvent) {
        if (protection.isProtected(event.block.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockFromTo(event: org.bukkit.event.block.BlockFromToEvent) {
        if (protection.isProtected(event.toBlock.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBurn(event: org.bukkit.event.block.BlockBurnEvent) {
        if (protection.isProtected(event.block.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockSpread(event: org.bukkit.event.block.BlockSpreadEvent) {
        if (protection.isProtected(event.block.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockIgnite(event: org.bukkit.event.block.BlockIgniteEvent) {
        if (protection.isProtected(event.block.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (crossesProtectedBoundary(event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (crossesProtectedBoundary(event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        if (mayBypass(event.player)) return
        if (protection.isProtected(event.entity.location)) {
            event.isCancelled = true
            event.player?.sendActionBar(protectedMsg)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        if (!protection.isProtected(event.entity.location)) return
        val player = (event as? HangingBreakByEntityEvent)?.remover?.let(::responsiblePlayer)
        if (mayBypass(player)) return
        event.isCancelled = true
        player?.sendActionBar(protectedMsg)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProtectedEntityDamage(event: EntityDamageEvent) {
        if (!isProtectedDecoration(event.entity)) return
        val player = (event as? EntityDamageByEntityEvent)?.damager?.let(::responsiblePlayer)
        if (mayBypass(player)) return
        event.isCancelled = true
        player?.sendActionBar(protectedMsg)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProtectedEntityInteract(event: PlayerInteractEntityEvent) {
        if (!isProtectedDecoration(event.rightClicked) || mayBypass(event.player)) return
        event.isCancelled = true
        event.player.sendActionBar(protectedMsg)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        if (!protection.isProtected(event.rightClicked.location) || mayBypass(event.player)) return
        event.isCancelled = true
        event.player.sendActionBar(protectedMsg)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        if (!isProtectedDecoration(event.entity) || mayBypass(event.player)) return
        event.isCancelled = true
        event.player?.sendActionBar(protectedMsg)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        if (!protection.isProtected(event.vehicle.location)) return
        val player = event.attacker?.let(::responsiblePlayer)
        if (mayBypass(player)) return
        event.isCancelled = true
        player?.sendActionBar(protectedMsg)
    }

    private fun isProtectedDecoration(entity: org.bukkit.entity.Entity): Boolean =
        (entity is Hanging || entity is ArmorStand || entity is Display || entity is Interaction) &&
            protection.isProtected(entity.location)

    private fun responsiblePlayer(entity: org.bukkit.entity.Entity): Player? = when (entity) {
        is Player -> entity
        is Projectile -> entity.shooter as? Player
        else -> null
    }

    private fun mayBypass(player: Player?): Boolean =
        player?.hasPermission(PROTECTION_BYPASS_PERMISSION) == true

    private fun connectedBlocks(block: Block): Set<Block> {
        val connected = linkedSetOf(block)
        when (val data = block.blockData) {
            is Bisected -> connected += block.getRelative(
                if (data.half == Bisected.Half.TOP) BlockFace.DOWN else BlockFace.UP,
            )
            is Bed -> connected += block.getRelative(
                if (data.part == Bed.Part.FOOT) data.facing else data.facing.oppositeFace,
            )
        }
        return connected
    }

    private fun isProtectedBucketTarget(clicked: Block, face: BlockFace): Boolean =
        protection.isProtected(clicked.getRelative(face).location)

    private fun crossesProtectedBoundary(blocks: List<Block>, direction: BlockFace): Boolean =
        blocks.any { protection.isProtected(it.location) || protection.isProtected(it.getRelative(direction).location) }

    companion object {
        const val PROTECTION_BYPASS_PERMISSION = "enthusiakoth.protection.bypass"
    }
}
