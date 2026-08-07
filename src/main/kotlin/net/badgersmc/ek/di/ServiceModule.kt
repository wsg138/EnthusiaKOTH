package net.badgersmc.ek.di

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.ek.EnthusiaKothPlugin
import net.badgersmc.ek.application.CancellationReason
import net.badgersmc.ek.application.DisplayService
import net.badgersmc.ek.application.EventStarter
import net.badgersmc.ek.application.FireworkCelebrationService
import net.badgersmc.ek.application.FlareService
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ObjectiveMarkerService
import net.badgersmc.ek.application.PaymentJournalEntry
import net.badgersmc.ek.application.PaymentJournalStatus
import net.badgersmc.ek.application.PaymentRecoveryAction
import net.badgersmc.ek.application.PaymentRecoveryPolicy
import net.badgersmc.ek.application.PendingRefundRecovery
import net.badgersmc.ek.application.PendingRefundRecoveryAttempt
import net.badgersmc.ek.application.PendingRefundRecoveryResult
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
import net.badgersmc.ek.infrastructure.persistence.FilePaymentJournal
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
        scheduleService.flush()
        kothService.shutdown(CancellationReason.RELOAD)
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
    private val paymentJournal = FilePaymentJournal(
        file = File(plugin.dataFolder, "payment-journal.dat"),
        logger = { message, error ->
            plugin.logger.severe(message)
            error?.let { plugin.logger.severe(it.stackTraceToString()) }
        },
    )

    val lumaGuildsAdapter = LumaGuildsAdapter()
    val langService = LangService(plugin, NexusLocale("en_US"), net.badgersmc.ek.infrastructure.i18n.KothLang::class.java)
    val statsRepository = SqlStatsRepository(
        dataSource = dataSource,
        familyResolver = { arenaId -> arenas()[arenaId]?.family ?: arenaId },
        legacyStatsFile = File(plugin.dataFolder, "stats.yml"),
        logger = { message, error ->
            if (error == null) plugin.logger.info(message) else {
                plugin.logger.severe(message)
                plugin.logger.severe(error.stackTraceToString())
            }
        },
    ).also { it.init() }
    val fireworkService = FireworkCelebrationService(plugin)
    val discordWebhook = DiscordWebhookService(
        plugin,
        { config().discord.webhookUrl },
        { config().discord.enabled },
        lumaGuildsAdapter,
    )
    val zoneBorderService = ZoneBorderService(plugin)
    val restrictionService = RestrictionService(
        rulesForArena = { arenaId ->
            val family = arenas()[arenaId]?.family?.lowercase() ?: arenaId.lowercase()
            config().rules.rules[family] ?: RuleSet.PERMISSIVE
        },
    )
    val displayService = DisplayService(plugin, langService).also {
        plugin.server.pluginManager.registerEvents(it, plugin)
    }
    val objectiveMarkerService = ObjectiveMarkerService()
    val vaultEconomy = VaultEconomyAdapter(plugin)
    private val pendingRefundRecovery = PendingRefundRecovery(
        journal = paymentJournal,
        economy = vaultEconomy,
        logger = { message, error ->
            plugin.logger.severe(message)
            error?.let { plugin.logger.severe(it.stackTraceToString()) }
        },
    )
    private var refundProviderUnavailableLogged = false

    val kothService = KothService(
        cfgLoader = { config() },
        stats = statsRepository,
        economy = vaultEconomy,
        guilds = lumaGuildsAdapter,
        displayService = displayService,
        objectiveMarkerService = objectiveMarkerService,
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
        eventTerminated = restrictionService::clearEvent,
    )
    val startService = StartService(
        config = { config() },
        pluginReady = { plugin.isEnabled },
        hasConflictingEvent = { kothService.activeEvent != null },
        economy = vaultEconomy,
        starter = object : EventStarter {
            override fun start(
                arena: KothArena,
                kind: net.badgersmc.ek.domain.EventKind,
                delaySeconds: Int,
                payment: net.badgersmc.ek.application.PaymentReceipt?,
            ): Boolean = kothService.startEvent(
                arena = arena,
                kind = kind,
                delaySeconds = delaySeconds,
                paymentReceipt = payment,
            )

            override fun start(
                arena: KothArena,
                kind: net.badgersmc.ek.domain.EventKind,
                delaySeconds: Int,
                payment: net.badgersmc.ek.application.PaymentReceipt?,
                teamMode: net.badgersmc.ek.domain.TeamMode,
            ): Boolean = kothService.startEvent(
                arena = arena,
                kind = kind,
                delaySeconds = delaySeconds,
                paymentReceipt = payment,
                teamMode = teamMode,
            )
        },
        logError = { message, error ->
            plugin.logger.severe(message)
            error?.let { plugin.logger.severe(it.stackTraceToString()) }
        },
        paymentJournal = paymentJournal,
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
        discordWarningMinutes = {
            val discord = config().discord
            discord.preStartPingMinutes.takeIf { discord.enabled } ?: 0
        },
        discordWarningSink = discordWebhook::sendPreStart,
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
    ).also { it.register() }

    init {
        recoverOutstandingPayments()
    }

    private fun recoverOutstandingPayments() {
        paymentJournal.entries().forEach { entry ->
            when (PaymentRecoveryPolicy.actionFor(entry.status)) {
                PaymentRecoveryAction.AUTO_REFUND -> Unit
                PaymentRecoveryAction.MANUAL_RECONCILIATION -> requireManualReconciliation(entry)
                PaymentRecoveryAction.IGNORE -> Unit
            }
        }
        retryPendingPaymentRecovery()
    }

    private fun requireManualReconciliation(entry: PaymentJournalEntry) {
        val reason = when (entry.status) {
            PaymentJournalStatus.CHARGED ->
                "CHARGED does not prove the event was interrupted; a completed KOTH can remain CHARGED if its final SETTLED journal write failed"
            PaymentJournalStatus.PREPARED,
            PaymentJournalStatus.REFUNDING ->
                "the server may have stopped during an external economy operation"
            else -> "the durable payment state is ambiguous"
        }
        plugin.logger.severe(
            "KOTH payment ${entry.transactionId} for ${entry.payerId} amount ${entry.amount} is ${entry.status.name}; " +
                "$reason, so automatic recovery is unsafe and manual reconciliation is required",
        )
        Bukkit.getPlayer(entry.payerId)?.sendMessage(
            net.kyori.adventure.text.Component.text(
                "A KOTH payment of ${entry.amount} requires administrator review after the server restart.",
            ),
        )
    }

    fun retryPendingPaymentRecovery() {
        val attempts = pendingRefundRecovery.retryPending()
        if (attempts.isEmpty()) {
            refundProviderUnavailableLogged = false
            return
        }
        val unavailable = attempts.filter { it.result == PendingRefundRecoveryResult.ECONOMY_UNAVAILABLE }
        if (unavailable.isNotEmpty()) {
            if (!refundProviderUnavailableLogged) {
                plugin.logger.severe(
                    "${unavailable.size} KOTH refund(s) remain REFUND_PENDING because the Vault economy provider is unavailable; recovery will retry automatically",
                )
                unavailable.forEach { attempt ->
                    Bukkit.getPlayer(attempt.entry.payerId)?.sendMessage(langService.msg("command.error.refund_failed"))
                }
                refundProviderUnavailableLogged = true
            }
            return
        }
        refundProviderUnavailableLogged = false
        attempts.forEach(::handlePendingRefundAttempt)
    }

    private fun handlePendingRefundAttempt(attempt: PendingRefundRecoveryAttempt) {
        val entry = attempt.entry
        when (attempt.result) {
            PendingRefundRecoveryResult.REFUNDED -> {
                plugin.logger.warning(
                    "Recovered interrupted KOTH payment ${entry.transactionId}: refunded ${entry.amount} to ${entry.payerId}",
                )
                Bukkit.getPlayer(entry.payerId)?.sendMessage(
                    net.kyori.adventure.text.Component.text(
                        "Your interrupted KOTH payment of ${entry.amount} was refunded after the economy provider became available.",
                    ),
                )
            }
            PendingRefundRecoveryResult.REFUND_REJECTED -> {
                plugin.logger.severe(
                    "KOTH payment ${entry.transactionId} refund was rejected for ${entry.payerId} amount ${entry.amount}; it remains REFUND_PENDING and will retry",
                )
                Bukkit.getPlayer(entry.payerId)?.sendMessage(langService.msg("command.error.refund_failed"))
            }
            PendingRefundRecoveryResult.JOURNAL_TRANSITION_FAILED -> plugin.logger.severe(
                "KOTH payment ${entry.transactionId} could not advance its durable refund state; automatic recovery did not move money",
            )
            PendingRefundRecoveryResult.AMBIGUOUS_EXTERNAL_RESULT -> plugin.logger.severe(
                "KOTH payment ${entry.transactionId} refund call threw after entering REFUNDING; automatic retries are stopped and manual reconciliation is required",
            )
            PendingRefundRecoveryResult.REFUNDED_JOURNAL_FAILED -> plugin.logger.severe(
                "KOTH payment ${entry.transactionId} was refunded but REFUNDED could not be journaled; it remains REFUNDING and requires manual reconciliation before any further money movement",
            )
            PendingRefundRecoveryResult.ECONOMY_UNAVAILABLE -> Unit
        }
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
        scheduleService.flush()
        discordWebhook.shutdown()
        statsRepository.shutdown()
        (dataSource as? HikariDataSource)?.close()
    }
}
