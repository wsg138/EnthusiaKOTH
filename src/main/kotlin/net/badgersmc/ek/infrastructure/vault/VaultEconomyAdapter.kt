package net.badgersmc.ek.infrastructure.vault

import net.badgersmc.ek.application.PlayerEconomy
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class VaultEconomyAdapter(private val plugin: JavaPlugin) : PlayerEconomy {
    private fun provider(): Economy? =
        plugin.server.servicesManager.getRegistration(Economy::class.java)?.provider

    override fun isAvailable(): Boolean = provider() != null

    override fun balance(playerId: UUID): Double =
        provider()?.getBalance(Bukkit.getOfflinePlayer(playerId)) ?: 0.0

    override fun withdraw(playerId: UUID, amount: Double): Boolean {
        if (amount <= 0.0) return true
        return provider()?.withdrawPlayer(Bukkit.getOfflinePlayer(playerId), amount)?.transactionSuccess() == true
    }

    override fun deposit(playerId: UUID, amount: Double): Boolean {
        if (amount <= 0.0) return true
        return provider()?.depositPlayer(Bukkit.getOfflinePlayer(playerId), amount)?.transactionSuccess() == true
    }
}
