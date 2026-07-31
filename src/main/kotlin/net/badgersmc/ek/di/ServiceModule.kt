package net.badgersmc.ek.di

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.ek.EnthusiaKothPlugin
import net.badgersmc.ek.application.DisplayService
import net.badgersmc.ek.application.FireworkCelebrationService
import net.badgersmc.ek.application.FlareService
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.bukkit.ConfigLoader
import net.badgersmc.ek.infrastructure.bukkit.KothCommand
import net.badgersmc.ek.infrastructure.bukkit.KothListeners
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.i18n.KothLang
import net.badgersmc.ek.infrastructure.protection.RegionProtectionListener
import net.badgersmc.ek.infrastructure.protection.RegionProtectionService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.papi.KothPlaceholderExpansion
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.ek.infrastructure.restriction.RestrictionListener
import net.badgersmc.ek.infrastructure.restriction.RestrictionService
import net.badgersmc.ek.infrastructure.restriction.RuleSet
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.plugin.SimplePluginManager
import java.io.File
import javax.sql.DataSource
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.i18n.Locale as NexusLocale

class ServiceModule(plugin: EnthusiaKothPlugin) {

    private val configLoader = ConfigLoader(plugin)

    @Volatile private var _config: EnthusiaKothConfig = configLoader.load()
    @Volatile private var _arenas: Map<String, KothArena> = configLoader.loadArenas()

    fun config(): EnthusiaKothConfig = _config
    fun arenas(): Map<String, KothArena> = _arenas
    fun reload() {
        configLoader.reload()
        _config = configLoader.load()
        _arenas = configLoader.loadArenas()
    }

    private val dataSource: DataSource by lazy {
        val dbFile = File(plugin.dataFolder, "koth_stats.db")
        val cfg = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 2
            connectionTestQuery = "SELECT 1"
            poolName = "EnthusiaKOTH-Pool"
        }
        HikariDataSource(cfg)
    }

    val lumaGuildsAdapter = LumaGuildsAdapter()

    /** Nexus i18n LangService — loads from lang/en_US.yml */
    val langService = LangService(plugin, NexusLocale("en_US"), KothLang::class.java)

    val statsRepository = SqlStatsRepository(dataSource).also { it.init() }

    val fireworkService = FireworkCelebrationService(plugin)

    val discordWebhook = DiscordWebhookService(
        plugin = plugin,
        webhookUrl = { config().discord.webhookUrl },
        enabled = { config().discord.enabled },
        guilds = lumaGuildsAdapter,
    )

    val zoneBorderService = ZoneBorderService(plugin)

    val restrictionService = RestrictionService(
        rulesForArena = { arenaId -> config().rules.rules[arenaId] ?: RuleSet.PERMISSIVE },
    )

    val displayService = DisplayService(plugin)

    val kothService = KothService(
        cfgLoader = { config() },
        stats = statsRepository,
        guilds = lumaGuildsAdapter,
        displayService = displayService,
        fireworkService = fireworkService,
        discordWebhook = discordWebhook,
        zoneBorderService = zoneBorderService,
    )

    val scheduleService = ScheduleService(
        cfgLoader = { config() },
        kothService = kothService,
        arenas = { arenas() },
    )

    val flareService = FlareService(
        cfgLoader = { config() },
        kothService = kothService,
        arenas = { arenas() },
    )

    val kothCommand = KothCommand(
        plugin = plugin,
        cfgLoader = { config() },
        kothService = kothService,
        scheduleService = scheduleService,
        stats = statsRepository,
        guilds = lumaGuildsAdapter,
        flareService = flareService,
        arenas = { arenas() },
        reloadAction = { reload() },
    ).also { cmd ->
        val commandMap = try {
            val f = SimplePluginManager::class.java.getDeclaredField("commandMap")
            f.isAccessible = true; f.get(Bukkit.getPluginManager()) as CommandMap
        } catch (_: Exception) {
            return@also
        }
        val registered = object : org.bukkit.command.Command("ekoth", "EnthusiaKOTH command", "/ekoth <subcommand>", listOf("koth")) {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<String>): Boolean =
                cmd.onCommand(sender, this, label, args)
            override fun tabComplete(sender: org.bukkit.command.CommandSender, alias: String, args: Array<String>): MutableList<String> =
                cmd.onTabComplete(sender, this, alias, args) ?: mutableListOf()
        }
        commandMap.register("enthusiakoth", registered)
    }

    val kothListeners = KothListeners(
        cfgLoader = { config() },
        kothService = kothService,
        flareService = flareService,
        arenas = { arenas() },
        command = kothCommand,
    ).also {
        plugin.server.pluginManager.registerEvents(it, plugin)
    }

    val restrictionListener = RestrictionListener(
        kothService = kothService,
        restrictions = restrictionService,
    ).also {
        plugin.server.pluginManager.registerEvents(it, plugin)
    }

    val regionProtectionService = RegionProtectionService(
        arenas = { arenas() },
    )

    val regionProtectionListener = RegionProtectionListener(
        protection = regionProtectionService,
    ).also {
        plugin.server.pluginManager.registerEvents(it, plugin)
    }

    val papiExpansion = KothPlaceholderExpansion(
        kothService = kothService,
        stats = statsRepository,
        guilds = lumaGuildsAdapter,
        arenas = { arenas() },
    ).also {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            it.register()
        }
    }
}
