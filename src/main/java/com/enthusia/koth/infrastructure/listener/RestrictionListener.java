package com.enthusia.koth.infrastructure.listener;

import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.ports.CombatIntegrationPort;
import com.enthusia.koth.application.rules.RestrictionDecision;
import com.enthusia.koth.application.rules.RestrictionService;
import com.enthusia.koth.domain.rules.RestrictedItemType;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public final class RestrictionListener implements Listener {
    private final ActiveEventService activeEvents;
    private final RestrictionService restrictions;
    private final CombatIntegrationPort combat;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Listener adapter holds application services supplied by bootstrap.")
    public RestrictionListener(ActiveEventService activeEvents, RestrictionService restrictions, CombatIntegrationPort combat) {
        this.activeEvents = activeEvents;
        this.restrictions = restrictions;
        this.combat = combat;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        activeEvents.activeEvent().ifPresent(active -> {
            if (!active.isParticipant(event.getPlayer().getUniqueId())) {
                return;
            }
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
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player player = damagingPlayer(event);
        if (player == null) {
            return;
        }
        activeEvents.activeEvent().ifPresent(active -> {
            if (active.isPrivateTest() && event.getEntity() instanceof Player victim
                    && isInsideEventArea(active, player, victim)
                    && active.isParticipant(player.getUniqueId()) != active.isParticipant(victim.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            if (!active.isParticipant(player.getUniqueId())) {
                return;
            }
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

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player) || !event.isGliding()) {
            return;
        }
        activeEvents.activeEvent().ifPresent(active -> {
            if (!active.isParticipant(player.getUniqueId())) {
                return;
            }
            if (!active.arena().zone().contains(player.getLocation())) {
                return;
            }
            RestrictionDecision decision = restrictions.canUseElytra(player, active);
            if (!decision.allowed()) {
                event.setCancelled(true);
                player.sendActionBar(net.kyori.adventure.text.Component.text(decision.message()));
            } else {
                if (combat.isAvailable()) {
                    event.setCancelled(false);
                }
                combat.allowKothElytraIfSupported(player);
            }
        });
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFireworkLaunch(ProjectileLaunchEvent event) {
        ProjectileSource source = event.getEntity().getShooter();
        if (!(source instanceof Player player)) {
            return;
        }
        activeEvents.activeEvent().ifPresent(active -> {
            if (!active.isParticipant(player.getUniqueId()) || !active.arena().zone().contains(player.getLocation())) {
                return;
            }
            if (event.getEntity() instanceof org.bukkit.entity.Firework) {
                RestrictionDecision decision = restrictions.canUseElytra(player, active);
                if (decision.allowed() && combat.isAvailable()) {
                    event.setCancelled(false);
                }
            } else if (event.getEntity() instanceof org.bukkit.entity.EnderPearl) {
                restrictions.applyCooldown(player, RestrictedItemType.ENDER_PEARL);
            } else if (event.getEntity() instanceof org.bukkit.entity.AbstractWindCharge) {
                restrictions.applyCooldown(player, RestrictedItemType.WIND_CHARGE);
            }
        });
    }

    private Player damagingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Player player ? player : null;
        }
        return null;
    }

    private boolean isInsideEventArea(com.enthusia.koth.domain.event.ActiveEvent event, Player first, Player second) {
        return event.arena().zone().contains(first.getLocation()) || event.arena().zone().contains(second.getLocation());
    }
}
