package net.badgersmc.ek

import net.badgersmc.ek.di.ServiceModule
import org.bukkit.plugin.java.JavaPlugin

class EnthusiaKothPlugin : JavaPlugin() {
    companion object {
        internal const val MIN_LUMAGUILDS_VERSION = "2.1.8"

        internal fun isVersionAtLeast(current: String, minimum: String): Boolean {
            fun numericParts(version: String): List<Int>? {
                val prefix = Regex("^(\\d+(?:\\.\\d+)*)").find(version.trim())?.value ?: return null
                return prefix.split('.').mapNotNull { it.toIntOrNull() }
            }

            val currentParts = numericParts(current) ?: return false
            val minimumParts = numericParts(minimum) ?: return false
            val count = maxOf(currentParts.size, minimumParts.size)
            for (index in 0 until count) {
                val currentPart = currentParts.getOrElse(index) { 0 }
                val minimumPart = minimumParts.getOrElse(index) { 0 }
                if (currentPart > minimumPart) return true
                if (currentPart < minimumPart) return false
            }
            return true
        }
    }

    lateinit var services: ServiceModule
        private set

    override fun onEnable() {
        dataFolder.mkdirs()
        saveDefaultConfig()

        val lumaGuilds = server.pluginManager.getPlugin("LumaGuilds")
        val lumaVersion = lumaGuilds?.pluginMeta?.version
        if (lumaVersion == null || !isVersionAtLeast(lumaVersion, MIN_LUMAGUILDS_VERSION)) {
            logger.severe(
                "EnthusiaKOTH requires LumaGuilds >= $MIN_LUMAGUILDS_VERSION because guild rewards use GuildLookup.getGuildMemberIds(); " +
                    "found ${lumaVersion ?: "no loaded LumaGuilds plugin"}",
            )
            server.pluginManager.disablePlugin(this)
            return
        }

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
        server.scheduler.runTaskTimer(this, services::retryPendingPaymentRecovery, 20L * 5L, 20L * 30L)
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
