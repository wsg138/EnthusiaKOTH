package net.badgersmc.ek

import net.badgersmc.ek.di.ServiceModule
import org.bukkit.plugin.java.JavaPlugin

class EnthusiaKothPlugin : JavaPlugin() {

    lateinit var services: ServiceModule
        private set

    override fun onEnable() {
        dataFolder.mkdirs()
        saveDefaultConfig()

        services = ServiceModule(this)

        server.scheduler.runTaskTimer(this, services.kothService::tick, 20L, 20L)
        server.scheduler.runTaskTimer(this, services.scheduleService::tick, 20L, 20L)

        logger.info("EnthusiaKOTH enabled (v${pluginMeta.version})")
    }

    override fun onDisable() {
        services.kothService.shutdown()
        services.discordWebhook.shutdown()
        logger.info("EnthusiaKOTH disabled")
    }
}
