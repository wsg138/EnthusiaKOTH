package net.badgersmc.ek.infrastructure.restriction

import net.badgersmc.ek.domain.KothEvent
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Types of items that can be restricted per arena. */
enum class RestrictedItemType {
    ELYTRA, MACE, SPEAR, ENDER_PEARL, WIND_CHARGE
}

/** Per-arena mace policy. */
enum class MaceRule {
    FULLY_DISABLED, BREACH_DISABLED, DENSITY_DISABLED, FULLY_ALLOWED
}

data class RuleSet(
    val elytraAllowed: Boolean = true,
    val maceRule: MaceRule = MaceRule.FULLY_ALLOWED,
    val spearAllowed: Boolean = true,
    val enderPearlAllowed: Boolean = true,
    val windChargeAllowed: Boolean = true,
    val maceCooldownSeconds: Int = 0,
    val spearCooldownSeconds: Int = 0,
    val enderPearlCooldownSeconds: Int = 0,
    val windChargeCooldownSeconds: Int = 0,
) {
    companion object {
        val PERMISSIVE = RuleSet()
    }
}

data class RestrictionDecision(
    val allowed: Boolean,
    val message: String,
    val cooldownSeconds: Int,
) {
    companion object {
        fun allowed(cooldownSeconds: Int = 0) = RestrictionDecision(true, "", cooldownSeconds)
        fun denied(message: String) = RestrictionDecision(false, message, 0)
    }
}

/** Immutable launch-time classification used when a projectile later enters an arena. */
data class ProjectileUseSnapshot(
    val eventId: UUID,
    val projectileId: UUID,
    val shooterId: UUID,
    val item: ItemStack?,
    val launchedInsideZone: Boolean,
)

/**
 * Evaluates restrictions and owns all event-scoped transient state.
 *
 * Cooldowns and projectile launch snapshots are keyed by event id. A later event can therefore
 * never inherit state even if a lifecycle callback is missed, while [clearEvent] still releases
 * references immediately on every normal termination path.
 */
class RestrictionService(
    private val rulesForArena: (arenaId: String) -> RuleSet,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class EventState(
        val cooldowns: MutableMap<UUID, MutableMap<RestrictedItemType, Instant>> = ConcurrentHashMap(),
        val projectiles: MutableMap<UUID, ProjectileUseSnapshot> = ConcurrentHashMap(),
    )

    private val states = ConcurrentHashMap<UUID, EventState>()

    fun canUseItem(player: Player, event: KothEvent, item: ItemStack?): RestrictionDecision {
        val type = item?.type?.let(::typeFor) ?: return RestrictionDecision.allowed()
        return decision(player, event, type)
    }

    fun canUseType(player: Player, event: KothEvent, type: RestrictedItemType): RestrictionDecision =
        decision(player, event, type)

    fun canUseTypeIgnoringCooldown(event: KothEvent, type: RestrictedItemType): RestrictionDecision {
        val rules = rulesForArena(event.arena.id)
        return if (enabled(rules, type)) {
            RestrictionDecision.allowed(cooldownFor(rules, type))
        } else {
            RestrictionDecision.denied("${type.name.lowercase().replace('_', ' ')} is disabled for this KOTH.")
        }
    }

    fun canDealDamageWith(player: Player, event: KothEvent, item: ItemStack?): RestrictionDecision {
        if (item == null) return RestrictionDecision.allowed()
        val rules = rulesForArena(event.arena.id)
        return when (item.type) {
            Material.MACE -> when (rules.maceRule) {
                MaceRule.FULLY_DISABLED -> RestrictionDecision.denied("Mace damage is disabled for this KOTH.")
                MaceRule.BREACH_DISABLED -> if (item.containsEnchantment(Enchantment.BREACH)) {
                    RestrictionDecision.denied("The Breach enchantment is disabled for this KOTH.")
                } else {
                    decision(player, event, RestrictedItemType.MACE)
                }
                MaceRule.DENSITY_DISABLED -> if (item.containsEnchantment(Enchantment.DENSITY)) {
                    RestrictionDecision.denied("The Density enchantment is disabled for this KOTH.")
                } else {
                    decision(player, event, RestrictedItemType.MACE)
                }
                MaceRule.FULLY_ALLOWED -> decision(player, event, RestrictedItemType.MACE)
            }
            else -> if (item.type in SPEARS) {
                decision(player, event, RestrictedItemType.SPEAR)
            } else {
                RestrictionDecision.allowed()
            }
        }
    }

    fun canUseElytra(player: Player, event: KothEvent): RestrictionDecision =
        decision(player, event, RestrictedItemType.ELYTRA)

    fun applyCooldown(player: Player, event: KothEvent, type: RestrictedItemType) {
        states.computeIfAbsent(event.id) { EventState() }
            .cooldowns
            .computeIfAbsent(player.uniqueId) { EnumMap(RestrictedItemType::class.java) }[type] = clock.instant()
    }

    fun recordProjectile(
        event: KothEvent,
        projectileId: UUID,
        player: Player,
        item: ItemStack?,
        launchedInsideZone: Boolean = false,
    ) {
        val snapshot = ProjectileUseSnapshot(
            eventId = event.id,
            projectileId = projectileId,
            shooterId = player.uniqueId,
            item = item?.clone(),
            launchedInsideZone = launchedInsideZone,
        )
        states.computeIfAbsent(event.id) { EventState() }.projectiles[projectileId] = snapshot
    }

    fun projectileSnapshot(event: KothEvent, projectileId: UUID): ProjectileUseSnapshot? =
        states[event.id]?.projectiles?.get(projectileId)?.takeIf { it.eventId == event.id }

    fun removeProjectile(eventId: UUID, projectileId: UUID) {
        states[eventId]?.projectiles?.remove(projectileId)
    }

    fun clearEvent(eventId: UUID) {
        states.remove(eventId)
    }

    fun clear() {
        states.clear()
    }

    internal fun trackedProjectileCount(eventId: UUID): Int = states[eventId]?.projectiles?.size ?: 0

    private fun decision(player: Player, event: KothEvent, type: RestrictedItemType): RestrictionDecision {
        val rules = rulesForArena(event.arena.id)
        val cooldownSeconds = cooldownFor(rules, type)
        if (!enabled(rules, type)) {
            return RestrictionDecision.denied("${type.name.lowercase().replace('_', ' ')} is disabled for this KOTH.")
        }
        val remaining = remainingCooldown(player, event, type, cooldownSeconds)
        return if (remaining > 0) {
            RestrictionDecision.denied("Still on cooldown for ${remaining}s.")
        } else {
            RestrictionDecision.allowed(cooldownSeconds)
        }
    }

    private fun enabled(rules: RuleSet, type: RestrictedItemType): Boolean = when (type) {
        RestrictedItemType.ELYTRA -> rules.elytraAllowed
        RestrictedItemType.MACE -> rules.maceRule != MaceRule.FULLY_DISABLED
        RestrictedItemType.SPEAR -> rules.spearAllowed
        RestrictedItemType.ENDER_PEARL -> rules.enderPearlAllowed
        RestrictedItemType.WIND_CHARGE -> rules.windChargeAllowed
    }

    private fun cooldownFor(rules: RuleSet, type: RestrictedItemType): Int = when (type) {
        RestrictedItemType.MACE -> rules.maceCooldownSeconds
        RestrictedItemType.SPEAR -> rules.spearCooldownSeconds
        RestrictedItemType.ENDER_PEARL -> rules.enderPearlCooldownSeconds
        RestrictedItemType.WIND_CHARGE -> rules.windChargeCooldownSeconds
        RestrictedItemType.ELYTRA -> 0
    }

    private fun remainingCooldown(
        player: Player,
        event: KothEvent,
        type: RestrictedItemType,
        cooldownSeconds: Int,
    ): Int {
        if (cooldownSeconds <= 0) return 0
        val last = states[event.id]?.cooldowns?.get(player.uniqueId)?.get(type) ?: return 0
        val elapsed = Duration.between(last, clock.instant()).seconds
        return (cooldownSeconds - elapsed).coerceAtLeast(0).toInt()
    }

    companion object {
        private val SPEARS: Set<Material> = listOf(
            "WOODEN_SPEAR", "STONE_SPEAR", "COPPER_SPEAR", "IRON_SPEAR",
            "GOLDEN_SPEAR", "DIAMOND_SPEAR", "NETHERITE_SPEAR", "SPEAR",
        ).mapNotNull { Material.getMaterial(it) }.toSet()

        fun typeFor(material: Material): RestrictedItemType? = when (material) {
            Material.ELYTRA -> RestrictedItemType.ELYTRA
            Material.MACE -> RestrictedItemType.MACE
            Material.ENDER_PEARL -> RestrictedItemType.ENDER_PEARL
            Material.WIND_CHARGE -> RestrictedItemType.WIND_CHARGE
            else -> if (material in SPEARS) RestrictedItemType.SPEAR else null
        }
    }
}
