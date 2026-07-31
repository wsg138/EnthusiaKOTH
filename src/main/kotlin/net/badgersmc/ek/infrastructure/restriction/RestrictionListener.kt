package net.badgersmc.ek.infrastructure.restriction

import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.toComponent
import org.bukkit.Material
import org.bukkit.entity.AbstractWindCharge
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.projectiles.ProjectileSource

/**
 * Bukkit event listener that enforces item-use restrictions during an active KOTH.
 *
 * Hooks into [KothService] to read the current [KothEvent] and applies
 * [RestrictionService] decisions. Denied actions are cancelled and the player
 * receives a coloured action-bar message.
 */
class RestrictionListener(
    private val kothService: KothService,
    private val restrictions: RestrictionService,
) : Listener {

    // ─────────────────────────────────────────────────────────
    // Item interaction (ender pearl, wind charge, spear, mace use)
    // ─────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val active = kothService.activeEvent ?: return
        val player = event.player

        if (!active.isParticipant(player.uniqueId)) return
        if (!active.arena.zone.contains(player.location)) return

        val decision = restrictions.canUseItem(player, active, event.item)
        if (!decision.allowed) {
            event.isCancelled = true
            player.sendActionBar(decision.message.toComponent())
        }
    }

    // ─────────────────────────────────────────────────────────
    // Damage (melee: mace, spear; projectile)
    // ─────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = damagingPlayer(event) ?: return
        val active = kothService.activeEvent ?: return

        // Private test — prevent cross-participant damage
        if (active.isPrivateTest && event.entity is Player) {
            val victim = event.entity as Player
            if (isInsideEventArea(active, player, victim)
                && active.isParticipant(player.uniqueId) != active.isParticipant(victim.uniqueId)
            ) {
                event.isCancelled = true
                return
            }
        }

        if (!active.isParticipant(player.uniqueId)) return
        if (!active.arena.zone.contains(player.location)) return

        val decision = restrictions.canDealDamageWith(player, active, player.inventory.itemInMainHand)
        if (!decision.allowed) {
            event.isCancelled = true
            player.sendActionBar(decision.message.toComponent())
        } else if (decision.cooldownSeconds > 0) {
            // Apply cooldown for mace or spear hits
            val hand = player.inventory.itemInMainHand
            val type = if (hand.type == Material.MACE) RestrictedItemType.MACE else RestrictedItemType.SPEAR
            restrictions.applyCooldown(player, type)
        }
    }

    // ─────────────────────────────────────────────────────────
    // Elytra toggle
    // ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGlide(event: EntityToggleGlideEvent) {
        if (event.entity !is Player || !event.isGliding) return
        val player = event.entity as Player
        val active = kothService.activeEvent ?: return

        if (!active.isParticipant(player.uniqueId)) return
        if (!active.arena.zone.contains(player.location)) return

        val decision = restrictions.canUseElytra(player, active)
        if (!decision.allowed) {
            event.isCancelled = true
            player.sendActionBar(decision.message.toComponent())
        }
    }

    // ─────────────────────────────────────────────────────────
    // Projectile launch (firework boost, ender pearl, wind charge)
    // ─────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val source = event.entity.shooter
        if (source !is Player) return
        val player = source
        val active = kothService.activeEvent ?: return

        if (!active.isParticipant(player.uniqueId) || !active.arena.zone.contains(player.location)) return

        when (event.entity) {
            is Firework -> {
                // Firework boosting — only allow if elytra is permitted
                val decision = restrictions.canUseElytra(player, active)
                if (!decision.allowed) {
                    event.isCancelled = true
                    player.sendActionBar(decision.message.toComponent())
                }
            }

            is EnderPearl -> {
                restrictions.applyCooldown(player, RestrictedItemType.ENDER_PEARL)
            }

            is AbstractWindCharge -> {
                restrictions.applyCooldown(player, RestrictedItemType.WIND_CHARGE)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private fun damagingPlayer(event: EntityDamageByEntityEvent): Player? {
        when (val damager = event.damager) {
            is Player -> return damager
            is Projectile -> {
                val shooter = damager.shooter
                if (shooter is Player) return shooter
            }
        }
        return null
    }

    private fun isInsideEventArea(
        event: net.badgersmc.ek.domain.KothEvent,
        first: Player,
        second: Player,
    ): Boolean {
        return event.arena.zone.contains(first.location)
            || event.arena.zone.contains(second.location)
    }
}