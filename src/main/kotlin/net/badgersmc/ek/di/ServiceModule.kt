package net.badgersmc.ek.di

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.ek.EnthusiaKothPlugin
import net.badgersmc.ek.application.DisplayService
import net.badgersmc.ek.application.EventStarter
import net.badgersmc.ek.application.FireworkCelebrationService
import net.badgersmc.ek.application.FlareService
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.application.StartService
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.LockConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.bukkit.ConfigLoader
import net.badgersmc.ek.infrastructure.bukkit.KothCommand
import net.badgersmc.ek.infrastructure.bukkit.KothListeners
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.papi.KothPlaceholderExpansion
import net.badgersmc.ek.infrastructure.persistence.FileOperationalStateStore
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.ek.infrastructure.protection.RegionProtectionListener
import net.badgersmc.ek.infrastructure.protection.RegionProtectionService
import net.badgersmc.ek.infrastructure.restriction.RestrictionListener
import net.badgersmc.ek.infrastructure.restriction.RestrictionService
import net.badgersmc.ek.infrastructure.restriction.RuleSet
import net.badgersmc.ek.infrastructure.vault.VaultEconomyAdapter
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.i18n.Locale as NexusLocale
import org.bukkit.Bukkit
import java.io.File
import java.time.Clock
import javax.sql.DataSource

class ServiceModule(private val plugin: EnthusiaKothPlugin) {
    private val configLoader = ConfigLoader(plugin)
    private val clock: Clock = Clock.systemUTC()
    @Volatile private var _config: EnthusiaKothConfig = configLoader.load()
    @Volatile private var _arenas: Map<String, KothArena> = configLoader.loadArenas()

    fun config(): EnthusiaKothConfig = _config
    fun arenas(): Map<String, KothArena> = _arenas

    fun setLockState(state: LockState) {
        plugin.config.set("locks.state", state.name)
        plugin.saveConfig()
        _config = _config.copy(locks = LockConfig(state))
    }

    fun reload() {
        kothService.shutdown()
        scheduleService.reload()
        restrictionService.clear()
        configLoader.reload()
        _config = configLoader.load()
        _arenas = configLoader.loadArenas()
        langService.reload()
        kothService.processQueue()
    }

    private val dataSource: DataSource by lazy {
        val cfg = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${File(plugin.dataFolder, "koth_stats.db").absolutePath}"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 2
            connectionTestQuery = "SELECT 1"
            poolName = "EnthusiaKOTH-Pool"
        }
        HikariDataSource(cfg)
    }

    private val operationalState = FileOperationalStateStore(
        scheduleFile = File(plugin.dataFolder, "schedule-state.dat"),
        queueFile = File(plugin.dataFolder, "event-queue.dat"),
        logger = { message, error ->
            plugin.logger.severe(message)
            error?.let { plugin.logger.severe(it.stackTraceToString()) }
        },
    )

    val lumaGuildsAdapter = LumaGuildsAdapter()
    val langService = LangService(plugin, NexusLocale("en_US"), net.badgersmc.ek.infrastructure.i18n.KothLang::class.java)
    val statsRepository = SqlStatsRepository(dataSource).also { it.init() }
    val fireworkService = FireworkCelebrationService(plugin)
    val discordWebhook = DiscordWebhookService(
        plugin,
        { config().discord.webhookUrl },
        { config().discord.enabled },
        lumaGuildsAdapter,
    )
    val zoneBorderService = ZoneBorderService(plugin)
    val restrictionService = RestrictionService { arenaId ->
        val family = arenas()[arenaId]?.family?.lowercase() ?: arenaId.lowercase()
        config().rules.rules[family] ?: RuleSet.PERMISSIVE
    }
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
        arenaResolver = { arenas()[it] },
        queueStore = operationalState,
        clock = clock,
        logger = { message, error ->
            plugin.logger.severe(message)
            error?.let { plugin.logger.severe(it.stackTraceToString()) }
        },
    )
    val vaultEconomy = VaultEconomyAdapter(plugin)
    val startService = StartService(
        config = { config() },
        pluginReady = { plugin.isEnabled },
        hasConflictingEvent = { kothService.activeEvent != null },
        economy = vaultEconomy,
        starter = EventStarter { arena, kind, delay ->
            kothService.startEvent(arena, kind = kind, delaySeconds = delay)
        },
        logError = { message, error ->
            plugin.logger.severe(message)
            error?.let { plugin.logger.severe(it.stackTraceToString()) }
        },
    )
    val scheduleService = ScheduleService(
        cfgLoader = { config() },
        kothService = kothService,
        arenas = { arenas() },
        stateStore = operationalState,
        clock = clock,
        warningSink = { arenaId, minutes ->
            Bukkit.getOnlinePlayers().forEach { player ->
                player.sendMessage(
                    langService.msg(
                        "koth.warning_minutes",
                        "koth_name" to arenaId,
                        "minutes" to minutes.toString(),
                    ),
                )
            }
        },
        logger = plugin.logger::warning,
    )
    val flareService = FlareService(
        cfgLoader = { config() },
        startService = startService,
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
        startService = startService,
        lang = langService,
        arenas = { arenas() },
        reloadAction = { reload() },
        lockAction = { setLockState(it) },
    ).also(::registerCommand)
    val kothListeners = KothListeners(
        cfgLoader = { config() },
        kothService = kothService,
        flareService = flareService,
        arenas = { arenas() },
        command = kothCommand,
        lang = langService,
    ).also { plugin.server.pluginManager.registerEvents(it, plugin) }
    val restrictionListener = RestrictionListener(kothService, restrictionService).also {
        plugin.server.pluginManager.registerEvents(it, plugin)
    }
    val regionProtectionService = RegionProtectionService { arenas() }
    val regionProtectionListener = RegionProtectionListener(regionProtectionService, langService).also {
        plugin.server.pluginManager.registerEvents(it, plugin)
    }
    val papiExpansion = KothPlaceholderExpansion(
        kothService,
        scheduleService,
        statsRepository,
        lumaGuildsAdapter,
        { arenas() },
        clock,
    ).also {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) it.register()
    }

    private fun registerCommand(cmd: KothCommand) {
        val commandMap = Bukkit.getCommandMap() ?: run {
            plugin.logger.warning("EnthusiaKOTH: no CommandMap available — /ekoth not registered")
            return
        }
        val command = object : org.bukkit.command.Command("ekoth", "EnthusiaKOTH command", "/ekoth <subcommand>", listOf("koth")) {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<String>): Boolean =
                cmd.onCommand(sender, this, label, args)
            override fun tabComplete(sender: org.bukkit.command.CommandSender, alias: String, args: Array<String>): MutableList<String> =
                cmd.onTabComplete(sender, this, alias, args) ?: mutableListOf()
        }
        commandMap.register("enthusiakoth", command)
    }

    fun shutdown() {
        discordWebhook.shutdown()
        statsRepository.shutdown()
        (dataSource as? HikariDataSource)?.close()
    }
}
