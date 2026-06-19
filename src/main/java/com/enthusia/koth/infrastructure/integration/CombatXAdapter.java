package com.enthusia.koth.infrastructure.integration;

import com.enthusia.koth.application.ports.CombatIntegrationPort;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CombatXAdapter implements CombatIntegrationPort {
    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("CombatX") != null || Bukkit.getPluginManager().getPlugin("CombatLogX") != null;
    }

    @Override
    public void allowKothElytraIfSupported(Player player) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("EnthusiaKOTH");
        if (plugin != null) {
            player.setMetadata("enthusiakoth-elytra-allowed", new org.bukkit.metadata.FixedMetadataValue(plugin, true));
        }
    }

    @Override
    public void clearKothOverride(Player player) {
        var plugin = Bukkit.getPluginManager().getPlugin("EnthusiaKOTH");
        if (plugin != null) {
            player.removeMetadata("enthusiakoth-elytra-allowed", plugin);
        }
    }
}
