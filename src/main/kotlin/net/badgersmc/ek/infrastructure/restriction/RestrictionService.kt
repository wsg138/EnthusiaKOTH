package net.badgersmc.ek.infrastructure.restriction

import net.badgersmc.ek.domain.KothEvent
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Types of items that can be restricted per-arena.
 */
enum class RestrictedItemType {
    ELYTRA, MACE, SPEAR, ENDER_PEARL, WIND_CHARGE
}

/**
 * How the mace enchantments are restricted.
 * - FULLY_DISABLED:  mace damage is blocked entirely
 * - BREACH_DISABLED: base mace damage is allowed, Breach enchant is blocked (enforced elsewhere)
 * - DENSITY_DISABLED: base mace damage is allowed, Density enchant is blocked (enforced elsewhere)
 * - FULLY_ALLOWED:   mace is unrestricted (still subject to per-item cooldown)
 */
enum class MaceRule {
    FULLY_DISABLED, BREACH_DISABLED, DENSITY_DISABLED, FULLY_ALLOWED
}

/**
 * Per-arena rule set for restricted items.
 * Matches the structure of config.yml [rules.defaults.<family>].
 */
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

/**
 * Result of a restriction check.
 */
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

/**
 * Evaluates item-use restrictions and tracks per-player per-item cooldowns
 * for an active KOTH event.
 *
 * @param rulesForArena resolves a [RuleSet] for the given arena id (typically from config)
 */
class RestrictionService(
    private val rulesForArena: (arenaId: String) -> RuleSet,
) {
    private val cooldowns: MutableMap<UUID, MutableMap<RestrictedItemType, Instant>> = mutableMapOf()

    // ─────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────

    /**
     * Check whether the player's held item is usable inside the event zone.
     * Covers direct-use items: ender pearl, wind charge, and (via the listener) spears / maces
     * when interacted with.
     */
    fun canUseItem(player: Player, event: KothEvent, item: ItemStack?): RestrictionDecision {
        if (item == null) return RestrictionDecision.allowed()
        val type = typeFor(item.type) ?: return RestrictionDecision.allowed()
        return decision(player, event, type)
    }

    /**
     * Check whether the player may deal damage with the held item (melee / projectile).
     * Applies mace-rule logic and spear restrictions.
     */
    fun canDealDamageWith(player: Player, event: KothEvent, item: ItemStack?): RestrictionDecision {
        if (item == null) return RestrictionDecision.allowed()
        val rules = rulesForArena(event.arena.id)
        return when (item.type) {
            Material.MACE -> when (rules.maceRule) {
                MaceRule.FULLY_DISABLED -> RestrictionDecision.denied("Mace damage is disabled for this KOTH.")
                // Base mace damage allowed, but the specific enchant is blocked —
                // enforce it here (the cooldown path alone only gates frequency).
                MaceRule.BREACH_DISABLED -> {
                    if (item.containsEnchantment(org.bukkit.enchantments.Enchantment.BREACH)) {
                        RestrictionDecision.denied("The Breach enchantment is disabled for this KOTH.")
                    } else {
                        decision(player, event, RestrictedItemType.MACE)
                    }
                }
                MaceRule.DENSITY_DISABLED -> {
                    if (item.containsEnchantment(org.bukkit.enchantments.Enchantment.DENSITY)) {
                        RestrictionDecision.denied("The Density enchantment is disabled for this KOTH.")
                    } else {
                        decision(player, event, RestrictedItemType.MACE)
                    }
                }
                MaceRule.FULLY_ALLOWED -> decision(player, event, RestrictedItemType.MACE)
            }

            else -> {
                if (item.type in SPEARS) {
                    decision(player, event, RestrictedItemType.SPEAR)
                } else {
                    RestrictionDecision.allowed()
                }
            }
        }
    }

    /**
     * Check whether the player may glide with an elytra.
     */
    fun canUseElytra(player: Player, event: KothEvent): RestrictionDecision =
        decision(player, event, RestrictedItemType.ELYTRA)

    /**
     * Record that a cooldown was triggered for the given item type.
     */
    fun applyCooldown(player: Player, type: RestrictedItemType) {
        cooldowns
            .computeIfAbsent(player.uniqueId) { EnumMap(RestrictedItemType::class.java) }
            .put(type, Instant.now())
    }

    /**
     * Clear all cooldowns (called on event end).
     */
    fun clear() {
        cooldowns.clear()
    }

    // ─────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────

    private fun decision(player: Player, event: KothEvent, type: RestrictedItemType): RestrictionDecision {
        val rules = rulesForArena(event.arena.id)
        val cooldownSeconds = cooldownFor(rules, type)

        if (!enabled(rules, type)) {
            return RestrictionDecision.denied(
                "${type.name.lowercase().replace('_', ' ')} is disabled for this KOTH."
            )
        }

        val remaining = remainingCooldown(player, type, cooldownSeconds)
        if (remaining > 0) {
            return RestrictionDecision.denied("Still on cooldown for ${remaining}s.")
        }

        return RestrictionDecision.allowed(cooldownSeconds)
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

    private fun remainingCooldown(player: Player, type: RestrictedItemType, cooldownSeconds: Int): Int {
        if (cooldownSeconds <= 0) return 0
        val last = cooldowns[player.uniqueId]?.get(type) ?: return 0
        val elapsed = Duration.between(last, Instant.now()).seconds
        return (cooldownSeconds - elapsed).coerceAtLeast(0).toInt()
    }

    companion object {
        /**
         * Spear materials resolved by name at runtime — the constants aren't part of
         * the vanilla Paper Material enum, so referencing them statically would break
         * compilation against artifacts that lack them. Unknown materials are skipped.
         */
        private val SPEARS: Set<Material> = listOf(
            "WOODEN_SPEAR", "STONE_SPEAR", "COPPER_SPEAR", "IRON_SPEAR",
            "GOLDEN_SPEAR", "DIAMOND_SPEAR", "NETHERITE_SPEAR",
        ).mapNotNull { runCatching { Material.getMaterial(it) }.getOrNull() }.toSet()

        private val typeFor: (Material) -> RestrictedItemType? = { material ->
            when (material) {
                Material.ELYTRA -> RestrictedItemType.ELYTRA
                Material.MACE -> RestrictedItemType.MACE
                Material.ENDER_PEARL -> RestrictedItemType.ENDER_PEARL
                Material.WIND_CHARGE -> RestrictedItemType.WIND_CHARGE
                else -> if (material in SPEARS) RestrictedItemType.SPEAR else null
            }
        }
    }
}
