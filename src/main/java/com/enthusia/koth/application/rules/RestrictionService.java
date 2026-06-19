package com.enthusia.koth.application.rules;

import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.rules.RestrictedItemType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RestrictionService {
    private static final Set<Material> SPEARS = Set.of(
            Material.WOODEN_SPEAR,
            Material.STONE_SPEAR,
            Material.COPPER_SPEAR,
            Material.IRON_SPEAR,
            Material.GOLDEN_SPEAR,
            Material.DIAMOND_SPEAR,
            Material.NETHERITE_SPEAR
    );

    private final Map<UUID, Map<RestrictedItemType, Instant>> cooldowns = new ConcurrentHashMap<>();

    public RestrictionDecision canUseItem(Player player, ActiveEvent event, ItemStack item) {
        if (item == null) {
            return RestrictionDecision.allowed(Duration.ZERO);
        }
        RestrictedItemType type = typeFor(item.getType());
        if (type == null) {
            return RestrictionDecision.allowed(Duration.ZERO);
        }
        return decision(player, event, type);
    }

    public RestrictionDecision canDealDamageWith(Player player, ActiveEvent event, ItemStack item) {
        if (item == null) {
            return RestrictionDecision.allowed(Duration.ZERO);
        }
        Material material = item.getType();
        if (material == Material.MACE) {
            return switch (event.request().rules().maceRule()) {
                case FULLY_DISABLED -> RestrictionDecision.denied("Mace damage is disabled for this KOTH.");
                case BREACH_DISABLED -> RestrictionDecision.allowed(event.request().rules().maceCooldown());
                case DENSITY_DISABLED -> RestrictionDecision.allowed(event.request().rules().maceCooldown());
                case FULLY_ALLOWED -> decision(player, event, RestrictedItemType.MACE);
            };
        }
        if (SPEARS.contains(material)) {
            return decision(player, event, RestrictedItemType.SPEAR);
        }
        return RestrictionDecision.allowed(Duration.ZERO);
    }

    public RestrictionDecision canUseElytra(Player player, ActiveEvent event) {
        return decision(player, event, RestrictedItemType.ELYTRA);
    }

    public void applyCooldown(Player player, RestrictedItemType type) {
        cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(RestrictedItemType.class))
                .put(type, Instant.now());
    }

    public void clear() {
        cooldowns.clear();
    }

    public String describeRestriction(ActiveEvent event, RestrictedItemType type) {
        return switch (type) {
            case ELYTRA -> event.request().rules().elytraAllowed() ? "Elytra allowed" : "Elytra disabled";
            case MACE -> "Mace: " + event.request().rules().maceRule();
            case SPEAR -> event.request().rules().spearAllowed() ? "Spear allowed" : "Spear disabled";
            case ENDER_PEARL -> event.request().rules().enderPearlAllowed() ? "Pearls allowed" : "Pearls disabled";
            case WIND_CHARGE -> event.request().rules().windChargeAllowed() ? "Wind charge allowed" : "Wind charge disabled";
        };
    }

    private RestrictionDecision decision(Player player, ActiveEvent event, RestrictedItemType type) {
        Duration cooldown = cooldownFor(event, type);
        if (!enabled(event, type)) {
            return RestrictionDecision.denied(type.name().toLowerCase().replace('_', ' ') + " is disabled for this KOTH.");
        }
        Duration remaining = remainingCooldown(player, type, cooldown);
        if (!remaining.isZero() && !remaining.isNegative()) {
            return RestrictionDecision.denied("Still on cooldown for " + remaining.toSeconds() + "s.");
        }
        return RestrictionDecision.allowed(cooldown);
    }

    private boolean enabled(ActiveEvent event, RestrictedItemType type) {
        return switch (type) {
            case ELYTRA -> event.request().rules().elytraAllowed();
            case MACE -> event.request().rules().maceRule() != com.enthusia.koth.domain.MaceRule.FULLY_DISABLED;
            case SPEAR -> event.request().rules().spearAllowed();
            case ENDER_PEARL -> event.request().rules().enderPearlAllowed();
            case WIND_CHARGE -> event.request().rules().windChargeAllowed();
        };
    }

    private Duration cooldownFor(ActiveEvent event, RestrictedItemType type) {
        return switch (type) {
            case MACE -> event.request().rules().maceCooldown();
            case SPEAR -> event.request().rules().spearCooldown();
            case ENDER_PEARL -> event.request().rules().enderPearlCooldown();
            case WIND_CHARGE -> event.request().rules().windChargeCooldown();
            case ELYTRA -> Duration.ZERO;
        };
    }

    private Duration remainingCooldown(Player player, RestrictedItemType type, Duration cooldown) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return Duration.ZERO;
        }
        Instant last = cooldowns.getOrDefault(player.getUniqueId(), Map.of()).get(type);
        if (last == null) {
            return Duration.ZERO;
        }
        return cooldown.minus(Duration.between(last, Instant.now()));
    }

    private RestrictedItemType typeFor(Material material) {
        if (material == Material.ELYTRA) return RestrictedItemType.ELYTRA;
        if (material == Material.MACE) return RestrictedItemType.MACE;
        if (SPEARS.contains(material)) return RestrictedItemType.SPEAR;
        if (material == Material.ENDER_PEARL) return RestrictedItemType.ENDER_PEARL;
        if (material == Material.WIND_CHARGE) return RestrictedItemType.WIND_CHARGE;
        return null;
    }
}
