package com.enthusia.koth.infrastructure.integration;

import com.enthusia.koth.application.ports.EconomyPort;
import com.enthusia.koth.application.ports.TransactionResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class VaultEconomyAdapter implements EconomyPort {
    private final JavaPlugin plugin;
    private Economy economy;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "JavaPlugin is the Bukkit-owned service lookup source.")
    public VaultEconomyAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            economy = null;
            return;
        }
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
    }

    @Override
    public boolean isAvailable() {
        return economy != null;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    @Override
    public TransactionResult withdraw(OfflinePlayer player, double amount, String reason) {
        if (economy == null) return TransactionResult.failure("Vault economy is unavailable.");
        var response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess() ? TransactionResult.success(reason) : TransactionResult.failure(response.errorMessage);
    }

    @Override
    public TransactionResult deposit(OfflinePlayer player, double amount, String reason) {
        if (economy == null) return TransactionResult.failure("Vault economy is unavailable.");
        var response = economy.depositPlayer(player, amount);
        return response.transactionSuccess() ? TransactionResult.success(reason) : TransactionResult.failure(response.errorMessage);
    }
}
