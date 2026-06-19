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
        player.setMetadata("enthusiakoth-elytra-allowed", new org.bukkit.metadata.FixedMetadataValue(Bukkit.getPluginManager().getPlugin("EnthusiaKOTH"), true));
    }

    @Override
    public void clearKothOverride(Player player) {
        var plugin = Bukkit.getPluginManager().getPlugin("EnthusiaKOTH");
        if (plugin != null) {
            player.removeMetadata("enthusiakoth-elytra-allowed", plugin);
        }
    }
}
