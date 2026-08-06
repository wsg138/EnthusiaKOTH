package net.badgersmc.ek

import net.badgersmc.ek.di.ServiceModule
import org.bukkit.plugin.java.JavaPlugin

class EnthusiaKothPlugin : JavaPlugin() {
    lateinit var services: ServiceModule
        private set

    override fun onEnable() {
        dataFolder.mkdirs()
        saveDefaultConfig()
        try {
            services = ServiceModule(this)
        } catch (error: Throwable) {
            logger.severe("EnthusiaKOTH failed to initialize: ${error.message}")
            error.printStackTrace()
            server.pluginManager.disablePlugin(this)
            return
        }
        server.scheduler.runTaskTimer(this, services.kothService::tick, 20L, 20L)
        server.scheduler.runTaskTimer(this, services.scheduleService::tick, 20L, 20L)
        logger.info("EnthusiaKOTH enabled (v${pluginMeta.version})")
    }

    override fun onDisable() {
        if (::services.isInitialized) {
            services.kothService.shutdown()
            services.shutdown()
        }
        logger.info("EnthusiaKOTH disabled")
    }
}
