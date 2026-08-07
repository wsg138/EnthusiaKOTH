package net.badgersmc.ek.infrastructure.restriction

import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.toComponent
import org.bukkit.Material
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.AbstractWindCharge
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.entity.ExplosionPrimeEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Enforces event-scoped item, projectile, and movement restrictions. */
class RestrictionListener(
    private val kothService: KothService,
    private val restrictions: RestrictionService,
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val active = relevantEvent(event.player) ?: return
        if (!active.arena.zone.contains(event.player.location)) return
        denyIfNeeded(event.player, restrictions.canUseItem(event.player, active, event.item)) {
            event.isCancelled = true
        }
    }

    /** Deny damage using the launch-time projectile item rather than the shooter's current hand. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val active = kothService.activeEvent ?: return
        val attackerId = damagingPlayerId(event, active)

        if (active.isPrivateTest && event.entity is Player) {
            val victim = event.entity as Player
            if (isInsideEventArea(active, event)
                && active.isParticipant(attackerId ?: UNKNOWN_ATTACKER) != active.isParticipant(victim.uniqueId)
            ) {
                event.isCancelled = true
                return
            }
        }

        val attacker = damagingPlayer(event) ?: return
        if (!active.isParticipant(attacker.uniqueId)) return
        val use = damageUse(event, active, attacker) ?: return
        if (!use.appliesInsideZone) return
        denyIfNeeded(attacker, damageDecision(attacker, active, use)) {
            event.isCancelled = true
        }
    }

    /** Apply hit cooldowns only after every other plugin has had a chance to cancel the damage. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAcceptedDamage(event: EntityDamageByEntityEvent) {
        if (event.isCancelled) return
        val active = kothService.activeEvent ?: return
        val attacker = damagingPlayer(event) ?: return
        if (!active.isParticipant(attacker.uniqueId)) return
        val use = damageUse(event, active, attacker) ?: return
        if (!use.appliesInsideZone) return
        val decision = damageDecision(attacker, active, use)
        val type = use.item?.type?.let(RestrictionService::typeFor) ?: return
        val shouldCommit = type in DAMAGE_COOLDOWNS
        if (decision.allowed && decision.cooldownSeconds > 0 && shouldCommit) {
            restrictions.applyCooldown(attacker, active, type)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGlide(event: EntityToggleGlideEvent) {
        if (event.isCancelled || event.entity !is Player || !event.isGliding) return
        val player = event.entity as Player
        val active = relevantEvent(player) ?: return
        if (!active.arena.zone.contains(player.location)) return
        denyIfNeeded(player, restrictions.canUseElytra(player, active)) {
            event.isCancelled = true
        }
    }

    /** Stops a player who was already gliding before crossing into the capture zone. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val active = relevantEvent(event.player) ?: return
        if (!event.player.isGliding) return
        if (active.arena.zone.contains(event.from) || !active.arena.zone.contains(to)) return
        val decision = restrictions.canUseElytra(event.player, active)
        if (!decision.allowed) {
            event.player.isGliding = false
            event.player.sendActionBar(decision.message.toComponent())
        }
    }

    /**
     * Reject restricted launches inside the zone. This handler never writes cooldown state;
     * accepted launch state is recorded at MONITOR after cancellation is final.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileRestriction(event: ProjectileLaunchEvent) {
        if (event.isCancelled) return
        val player = event.entity.shooter as? Player ?: return
        val active = relevantEvent(player) ?: return
        if (!active.arena.zone.contains(player.location)) return

        val decision = when (event.entity) {
            is Firework -> restrictions.canUseElytra(player, active)
            is EnderPearl -> restrictions.canUseType(player, active, RestrictedItemType.ENDER_PEARL)
            is AbstractWindCharge -> restrictions.canUseType(player, active, RestrictedItemType.WIND_CHARGE)
            else -> restrictions.canUseItem(player, active, launchItem(event.entity))
        }
        denyIfNeeded(player, decision) { event.isCancelled = true }
    }

    /** Track player-placed damaging entities (notably end crystals) for private-test isolation. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityPlaced(event: EntityPlaceEvent) {
        if (event.isCancelled) return
        val active = kothService.activeEvent ?: return
        if (!active.isPrivateTest) return
        val player = event.player ?: return
        restrictions.recordIndirectSource(active, event.entity.uniqueId, player.uniqueId)
    }

    /** Snapshot every accepted participant projectile, including launches outside the zone. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileAccepted(event: ProjectileLaunchEvent) {
        if (event.isCancelled) return
        val player = event.entity.shooter as? Player ?: return
        val active = relevantEvent(player) ?: return
        val launchedInside = active.arena.zone.contains(player.location)
        val item = launchItem(event.entity)
        restrictions.recordProjectile(
            active,
            event.entity.uniqueId,
            player,
            item,
            launchedInsideZone = launchedInside,
        )

        // Pearl cooldown begins only after a successful teleport, otherwise the pearl would
        // see its own just-written cooldown and cancel itself. Wind charges commit at launch.
        if (launchedInside && event.entity is AbstractWindCharge) {
            val decision = restrictions.canUseType(player, active, RestrictedItemType.WIND_CHARGE)
            if (decision.allowed && decision.cooldownSeconds > 0) {
                restrictions.applyCooldown(player, active, RestrictedItemType.WIND_CHARGE)
            }
        }
    }

    /** Outside-launched wind charges are evaluated when their effect reaches the zone. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onWindChargePrime(event: ExplosionPrimeEvent) {
        if (event.entity !is AbstractWindCharge) return
        val active = kothService.activeEvent ?: return
        if (!active.arena.zone.contains(event.entity.location)) return
        val snapshot = restrictions.projectileSnapshot(active, event.entity.uniqueId) ?: return
        val player = org.bukkit.Bukkit.getPlayer(snapshot.shooterId) ?: return
        val decision = if (snapshot.launchedInsideZone) {
            restrictions.canUseTypeIgnoringCooldown(active, RestrictedItemType.WIND_CHARGE)
        } else {
            restrictions.canUseType(player, active, RestrictedItemType.WIND_CHARGE)
        }
        denyIfNeeded(player, decision) { event.isCancelled = true }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAcceptedWindChargePrime(event: ExplosionPrimeEvent) {
        if (event.isCancelled || event.entity !is AbstractWindCharge) return
        val active = kothService.activeEvent ?: return
        if (!active.arena.zone.contains(event.entity.location)) return
        val snapshot = restrictions.projectileSnapshot(active, event.entity.uniqueId) ?: return
        if (snapshot.launchedInsideZone) return
        val player = org.bukkit.Bukkit.getPlayer(snapshot.shooterId) ?: return
        val decision = restrictions.canUseType(player, active, RestrictedItemType.WIND_CHARGE)
        if (decision.allowed && decision.cooldownSeconds > 0) {
            restrictions.applyCooldown(player, active, RestrictedItemType.WIND_CHARGE)
        }
    }

    /** Outside-launched pearls cannot teleport a participant into a zone where pearls are denied. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPearlTeleport(event: PlayerTeleportEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return
        val to = event.to ?: return
        val active = relevantEvent(event.player) ?: return
        if (!active.arena.zone.contains(to)) return
        denyIfNeeded(event.player, restrictions.canUseType(event.player, active, RestrictedItemType.ENDER_PEARL)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAcceptedPearlTeleport(event: PlayerTeleportEvent) {
        if (event.isCancelled || event.cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return
        val to = event.to ?: return
        val active = relevantEvent(event.player) ?: return
        if (!active.arena.zone.contains(to)) return
        val decision = restrictions.canUseType(event.player, active, RestrictedItemType.ENDER_PEARL)
        if (decision.allowed && decision.cooldownSeconds > 0) {
            restrictions.applyCooldown(event.player, active, RestrictedItemType.ENDER_PEARL)
        }
    }

    private fun relevantEvent(player: Player): KothEvent? =
        kothService.activeEvent?.takeIf { it.isParticipant(player.uniqueId) }

    private fun damageUse(event: EntityDamageByEntityEvent, active: KothEvent, attacker: Player): DamageUse? {
        val victimInside = active.arena.zone.contains(event.entity.location)
        return when (val damager = event.damager) {
            is Player -> DamageUse(damager.inventory.itemInMainHand, active.arena.zone.contains(damager.location) || victimInside)
            is Projectile -> {
                val snapshot = restrictions.projectileSnapshot(active, damager.uniqueId) ?: return null
                if (snapshot.shooterId != attacker.uniqueId) return null
                DamageUse(
                    item = snapshot.item,
                    appliesInsideZone = victimInside || active.arena.zone.contains(damager.location),
                )
            }
            else -> null
        }
    }

    private fun launchItem(projectile: Projectile): ItemStack? {
        when (projectile) {
            is EnderPearl -> return ItemStack(Material.ENDER_PEARL)
            is AbstractWindCharge -> return ItemStack(Material.WIND_CHARGE)
        }
        if (projectile is AbstractArrow) return projectile.weapon?.clone()
        return null
    }

    private fun damagingPlayer(event: EntityDamageByEntityEvent): Player? = when (val damager = event.damager) {
        is Player -> damager
        is Projectile -> damager.shooter as? Player
        else -> null
    }

    private fun damagingPlayerId(event: EntityDamageByEntityEvent, active: KothEvent): UUID? {
        damagingPlayer(event)?.let { return it.uniqueId }
        val damager = event.damager
        val causing = runCatching { event.damageSource.causingEntity }.getOrNull()
        if (causing is Player) return causing.uniqueId
        return causing?.let { restrictions.indirectSourceOwner(active, it.uniqueId) }
            ?: restrictions.indirectSourceOwner(active, damager.uniqueId)
    }

    private fun isInsideEventArea(event: KothEvent, damage: EntityDamageByEntityEvent): Boolean =
        event.arena.zone.contains(damage.entity.location) || event.arena.zone.contains(damage.damager.location)

    private inline fun denyIfNeeded(player: Player, decision: RestrictionDecision, deny: () -> Unit) {
        if (!decision.allowed) {
            deny()
            player.sendActionBar(decision.message.toComponent())
        }
    }

    private fun damageDecision(player: Player, event: KothEvent, use: DamageUse): RestrictionDecision {
        val type = use.item?.type?.let(RestrictionService::typeFor)
        return if (type == RestrictedItemType.WIND_CHARGE) {
            // Wind-charge cooldowns are committed after an accepted launch/prime. The same
            // charge's later damage must not be rejected by the cooldown it just started.
            restrictions.canUseTypeIgnoringCooldown(event, type)
        } else {
            restrictions.canDealDamageWith(player, event, use.item)
        }
    }

    private data class DamageUse(
        val item: ItemStack?,
        val appliesInsideZone: Boolean,
    )

    companion object {
        private val DAMAGE_COOLDOWNS = setOf(RestrictedItemType.MACE, RestrictedItemType.SPEAR)
        private val UNKNOWN_ATTACKER = UUID(0L, 0L)
    }
}
