package com.enthusia.koth.application.ports;

import org.bukkit.OfflinePlayer;

public interface EconomyPort {
    boolean isAvailable();
    boolean has(OfflinePlayer player, double amount);
    TransactionResult withdraw(OfflinePlayer player, double amount, String reason);
    TransactionResult deposit(OfflinePlayer player, double amount, String reason);
}
