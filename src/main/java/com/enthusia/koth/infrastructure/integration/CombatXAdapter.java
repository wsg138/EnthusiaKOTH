package com.enthusia.koth.infrastructure.integration;

import com.enthusia.koth.application.ports.CombatIntegrationPort;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class CombatXAdapter implements CombatIntegrationPort {
    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("CombatX") != null || Bukkit.getPluginManager().getPlugin("CombatLogX") != null;
    }

    @Override
    public void allowKothElytraIfSupported(Player player) {
        // CombatLogX has no per-player elytra exemption API. RestrictionListener restores an
        // allowed glide/firework event after CombatLogX's normal-priority listener cancels it.
    }

    @Override
    public void clearKothOverride(Player player) {
        // No per-player CombatLogX state is created by this integration.
    }
}
