package com.enthusia.koth.application.ports;

import org.bukkit.entity.Player;

public interface CombatIntegrationPort {
    boolean isAvailable();
    void allowKothElytraIfSupported(Player player);
    void clearKothOverride(Player player);
}
