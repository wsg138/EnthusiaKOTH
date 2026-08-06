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
import java.io.File
import javax.sql.DataSource
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.i18n.Locale as NexusLocale

class ServiceModule(private val plugin: EnthusiaKothPlugin) {

    private val configLoader = ConfigLoader(plugin)

    @Volatile private var _config: EnthusiaKothConfig = configLoader.load()
    @Volatile private var _arenas: Map<String, KothArena> = configLoader.loadArenas()

    fun config(): EnthusiaKothConfig = _config
    fun arenas(): Map<String, KothArena> = _arenas

    /**
     * Full reload cycle — `/ekoth reload`:
     * 1. Clean shutdown of the event subsystem: force-ends any active KOTH
     *    (clears bossbar + zone border), resets schedule state and cooldowns.
     * 2. Re-reads config.yml and re-parses config + arenas from disk.
     * 3. Re-reads the lang file so message edits apply without a restart.
     *
     * Tick timers keep running; services read config/arenas lazily through
     * the volatile holders, so they pick up the new values on the next tick.
     */
    fun reload() {
        kothService.shutdown()
        scheduleService.reset()
        kothService.clearQueue()
        restrictionService.clear()
        configLoader.reload()
        _config = configLoader.load()
        _arenas = configLoader.loadArenas()
        langService.reload()
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
        // Rules are configured per FAMILY (rules.defaults.<family>), so resolve
        // the arena's family first — an arena named differently from its family
        // (e.g. "capture_north") must not silently fall back to PERMISSIVE.
        rulesForArena = { arenaId ->
            val family = arenas()[arenaId]?.family?.lowercase() ?: arenaId.lowercase()
            config().rules.rules[family] ?: RuleSet.PERMISSIVE
        },
    )

    val displayService = DisplayService(plugin, langService).also {
        plugin.server.pluginManager.registerEvents(it, plugin)
    }

    val kothService = KothService(
        cfgLoader = { config() },
        stats = statsRepository,
        guilds = lumaGuildsAdapter,
        displayService = displayService,
        fireworkService = fireworkService,
        discordWebhook = discordWebhook,
        zoneBorderService = zoneBorderService,
        lang = langService,
    )

    val scheduleService = ScheduleService(
        cfgLoader = { config() },
        kothService = kothService,
        arenas = { arenas() },
        lang = langService,
    )

    val flareService = FlareService(
        cfgLoader = { config() },
        kothService = kothService,
        arenas = { arenas() },
        lang = langService,
    )

    val kothCommand = KothCommand(
        plugin = plugin,
        cfgLoader = { config() },
        kothService = kothService,
        scheduleService = scheduleService,
        stats = statsRepository,
        guilds = lumaGuildsAdapter,
        flareService = flareService,
        lang = langService,
        arenas = { arenas() },
        reloadAction = { reload() },
    ).also { cmd ->
        registerCommand(cmd)
    }

    val kothListeners = KothListeners(
        cfgLoader = { config() },
        kothService = kothService,
        flareService = flareService,
        arenas = { arenas() },
        command = kothCommand,
        lang = langService,
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
        lang = langService,
    ).also {
        plugin.server.pluginManager.registerEvents(it, plugin)
    }

    val papiExpansion = KothPlaceholderExpansion(
        kothService = kothService,
        scheduleService = scheduleService,
        stats = statsRepository,
        guilds = lumaGuildsAdapter,
        arenas = { arenas() },
    ).also {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            it.register()
        }
    }

    /**
     * Registers /ekoth through the public command API (Paper's Bukkit.getCommandMap())
     * instead of reflecting into SimplePluginManager's private field.
     */
    private fun registerCommand(cmd: KothCommand) {
        val commandMap = Bukkit.getCommandMap() ?: run {
            plugin.logger.warning("EnthusiaKOTH: no CommandMap available — /ekoth not registered")
            return
        }
        val registered = object : org.bukkit.command.Command("ekoth", "EnthusiaKOTH command", "/ekoth <subcommand>", listOf("koth")) {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<String>): Boolean =
                cmd.onCommand(sender, this, label, args)
            override fun tabComplete(sender: org.bukkit.command.CommandSender, alias: String, args: Array<String>): MutableList<String> =
                cmd.onTabComplete(sender, this, alias, args) ?: mutableListOf()
        }
        commandMap.register("enthusiakoth", registered)
    }

    /** Releases resources (HikariCP pool, stats writer) on plugin disable/reload. */
    fun shutdown() {
        (dataSource as? HikariDataSource)?.close()
    }
}
