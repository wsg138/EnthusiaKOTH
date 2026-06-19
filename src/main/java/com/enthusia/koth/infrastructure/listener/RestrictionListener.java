package com.enthusia.koth.infrastructure.listener;

import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.ports.CombatIntegrationPort;
import com.enthusia.koth.application.rules.RestrictionDecision;
import com.enthusia.koth.application.rules.RestrictionService;
import com.enthusia.koth.domain.rules.RestrictedItemType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public final class RestrictionListener implements Listener {
    private final ActiveEventService activeEvents;
    private final RestrictionService restrictions;
    private final CombatIntegrationPort combat;

    public RestrictionListener(ActiveEventService activeEvents, RestrictionService restrictions, CombatIntegrationPort combat) {
        this.activeEvents = activeEvents;
        this.restrictions = restrictions;
        this.combat = combat;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        activeEvents.activeEvent().ifPresent(active -> {
            if (!active.arena().zone().contains(event.getPlayer().getLocation())) {
                return;
            }
            ItemStack item = event.getItem();
            RestrictionDecision decision = restrictions.canUseItem(event.getPlayer(), active, item);
            if (!decision.allowed()) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(decision.message()));
                return;
            }
            if (item != null && item.getType() == Material.ENDER_PEARL) {
                restrictions.applyCooldown(event.getPlayer(), RestrictedItemType.ENDER_PEARL);
            } else if (item != null && item.getType() == Material.WIND_CHARGE) {
                restrictions.applyCooldown(event.getPlayer(), RestrictedItemType.WIND_CHARGE);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        activeEvents.activeEvent().ifPresent(active -> {
            if (!active.arena().zone().contains(player.getLocation())) {
                return;
            }
            RestrictionDecision decision = restrictions.canDealDamageWith(player, active, player.getInventory().getItemInMainHand());
            if (!decision.allowed()) {
                event.setCancelled(true);
                player.sendActionBar(net.kyori.adventure.text.Component.text(decision.message()));
            } else if (!decision.cooldown().isZero()) {
                Material type = player.getInventory().getItemInMainHand().getType();
                restrictions.applyCooldown(player, type == Material.MACE ? RestrictedItemType.MACE : RestrictedItemType.SPEAR);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player) || !event.isGliding()) {
            return;
        }
        activeEvents.activeEvent().ifPresent(active -> {
            if (!active.arena().zone().contains(player.getLocation())) {
                return;
            }
            RestrictionDecision decision = restrictions.canUseElytra(player, active);
            if (!decision.allowed()) {
                event.setCancelled(true);
                player.sendActionBar(net.kyori.adventure.text.Component.text(decision.message()));
            } else {
                combat.allowKothElytraIfSupported(player);
            }
        });
    }
}
