package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.FlareService
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.application.StartActor
import net.badgersmc.ek.application.StartFailure
import net.badgersmc.ek.application.StartRequest
import net.badgersmc.ek.application.StartResult
import net.badgersmc.ek.application.StartService
import net.badgersmc.ek.application.StartSource
import net.badgersmc.ek.application.StartTier
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.EventState
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class KothGuiHolder(val arenaIds: List<String>) : InventoryHolder {
    lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
}

class KothCommand(
    private val plugin: org.bukkit.plugin.java.JavaPlugin,
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val kothService: KothService,
    private val scheduleService: ScheduleService,
    private val stats: SqlStatsRepository,
    private val guilds: LumaGuildsAdapter,
    private val flareService: FlareService,
    private val startService: StartService,
    private val lang: LangService,
    private val arenas: () -> Map<String, KothArena>,
    private val reloadAction: () -> Unit,
    private val lockAction: (LockState) -> Unit,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "gui" -> gui(sender)
            "schedule" -> schedule(sender)
            "top" -> top(sender, args.getOrNull(1)?.toIntOrNull() ?: 1)
            "stats" -> stats(sender, args.getOrNull(1))
            "start" -> start(sender, args.getOrNull(1) ?: "", args.getOrNull(2))
            "stop", "cancel" -> stop(sender)
            "giveflare" -> giveFlare(sender, args.getOrNull(1) ?: "", args.getOrNull(2) ?: "", args.getOrNull(3)?.toIntOrNull() ?: 1)
            "reload" -> doReload(sender)
            "private" -> privateTest(sender, args.getOrNull(1) ?: "", args.getOrNull(2) ?: "")
            "status" -> status(sender)
            "lock" -> lock(sender, args.getOrNull(1) ?: "")
            else -> sendHelp(sender)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        if (args.size == 1) {
            val options = mutableListOf("gui", "schedule", "top", "stats", "private")
            if (sender.hasPermission("enthusiakoth.start.basic") || sender.hasPermission("enthusiakoth.start.advanced") || sender.hasPermission("enthusiakoth.admin")) {
                options += "start"
            }
            if (sender.hasPermission("enthusiakoth.admin")) {
                options += listOf("stop", "cancel", "giveflare", "reload", "status", "lock")
            }
            return options.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }
        if (args.size == 2) {
            when (args[0].lowercase()) {
                "start" -> return arenas().keys.filter { it.startsWith(args[1], true) }.toMutableList()
                "private" -> return listOf("start", "join", "leave", "cancel").filter { it.startsWith(args[1], true) }.toMutableList()
                "lock" -> return LockState.entries.map { it.name.lowercase() }.filter { it.startsWith(args[1], true) }.toMutableList()
            }
        }
        if (args.size == 3 && args[0].equals("start", true)) {
            return listOf("basic", "advanced").filter { it.startsWith(args[2], true) }.toMutableList()
        }
        if (args.size == 3 && args[0].equals("private", true) && args[1].equals("start", true)) {
            return arenas().keys.filter { it.startsWith(args[2], true) }.toMutableList()
        }
        return mutableListOf()
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(lang.msg("command.help.header"))
        listOf("gui", "schedule", "top", "stats").forEach { sender.sendMessage(lang.msg("command.help.$it")) }
        if (sender.hasPermission("enthusiakoth.start.basic") || sender.hasPermission("enthusiakoth.start.advanced") || sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.help.start"))
        }
        if (sender.hasPermission("enthusiakoth.admin")) {
            listOf("stop", "cancel", "giveflare", "reload", "status", "lock").forEach {
                sender.sendMessage(lang.msg("command.help.$it"))
            }
        }
    }

    private fun gui(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(lang.msg("command.error.not_a_player"))
            return
        }
        val arenaIds = arenas().keys.toList()
        val size = ((arenaIds.size + 8) / 9).coerceIn(1, 6) * 9
        val holder = KothGuiHolder(arenaIds)
        val inventory = Bukkit.createInventory(holder, size, lang.msg("command.gui.title"))
        holder.backingInventory = inventory
        arenaIds.take(size).forEachIndexed { slot, id ->
            val event = kothService.activeEvent
            val active = event?.arena?.id == id
            val capper = if (active) kothService.capperName(event!!) ?: "None" else "None"
            val time = if (active) formatTime(event!!.endsAt.epochSecond - System.currentTimeMillis() / 1000) else "Scheduled"
            val item = ItemStack(if (active) Material.GLOWSTONE_DUST else Material.REDSTONE_TORCH)
            item.itemMeta = item.itemMeta?.apply {
                displayName(lang.msg(if (active) "command.gui.item_name_active" else "command.gui.item_name_inactive", "id" to id.uppercase()))
                lore(
                    listOf(
                        lang.msg(if (active) "command.gui.lore_active_yes" else "command.gui.lore_active_no", "status" to active.toString()),
                        lang.msg(if (active) "command.gui.lore_capturing_yes" else "command.gui.lore_capturing_no", "capper" to capper),
                        lang.msg(if (active) "command.gui.lore_time_left_yes" else "command.gui.lore_time_left_no", "time" to time),
                    ),
                )
            }
            inventory.setItem(slot, item)
        }
        sender.openInventory(inventory)
    }

    private fun schedule(sender: CommandSender) {
        val cfg = cfgLoader()
        val now = ZonedDateTime.now(cfg.schedule.zone)
        sender.sendMessage(lang.msg("command.schedule.header"))
        sender.sendMessage(lang.msg("command.schedule.time_now", "time" to now.format(DateTimeFormatter.ofPattern("HH:mm"))))
        sender.sendMessage(lang.msg("command.schedule.timezone", "zone" to cfg.schedule.zone.id))
        sender.sendMessage(Component.empty())
        arenas().forEach { (id, arena) ->
            sender.sendMessage(lang.msg("command.schedule.entry", "name" to id))
            arena.schedule.ifEmpty { cfg.schedule.times }.forEach { time ->
                sender.sendMessage(lang.msg("command.schedule.entry_time", "time" to time))
            }
        }
    }

    private fun top(sender: CommandSender, page: Int) {
        val maximum = stats.maxPages().coerceAtLeast(1)
        val selected = page.coerceIn(1, maximum)
        sender.sendMessage(lang.msg("command.top.header"))
        stats.allWins().entries.sortedByDescending { it.value }.drop((selected - 1) * 10).take(10)
            .forEachIndexed { offset, entry ->
                sender.sendMessage(
                    lang.msg(
                        "command.top.entry",
                        "rank" to ((selected - 1) * 10 + offset + 1).toString(),
                        "player" to formatStatUser(entry.key),
                        "wins" to entry.value.toString(),
                    ),
                )
            }
    }

    private fun stats(sender: CommandSender, target: String?) {
        val player = target?.let(Bukkit::getPlayerExact) ?: sender as? Player
        if (player == null) {
            sender.sendMessage(lang.msg("command.error.player_not_found"))
            return
        }
        val wins = stats.totalWins("solo:${player.uniqueId}")
        sender.sendMessage(
            if (sender == player) lang.msg("command.error.stats_self", "wins" to wins.toString())
            else lang.msg("command.error.stats_other", "player" to player.name, "wins" to wins.toString()),
        )
    }

    private fun start(sender: CommandSender, arenaId: String, tierArg: String?) {
        val arena = arenas()[arenaId]
        if (arena == null) {
            sender.sendMessage(lang.msg("command.error.koth_not_found", "koths" to arenas().keys.joinToString(", ")))
            return
        }
        val tier = when (tierArg?.lowercase()) {
            null, "" -> null
            "basic" -> StartTier.BASIC
            "advanced" -> StartTier.ADVANCED
            else -> {
                sender.sendMessage(lang.msg("command.error.invalid_start_tier"))
                return
            }
        }
        val source = when {
            sender !is Player -> StartSource.CONSOLE
            sender.hasPermission("enthusiakoth.admin") -> StartSource.ADMIN_COMMAND
            else -> StartSource.PLAYER_COMMAND
        }
        sendStartResult(sender, arenaId, startService.start(StartRequest(startActor(sender), arena, source, tier)), false)
    }

    private fun startActor(sender: CommandSender) = StartActor(
        playerId = (sender as? Player)?.uniqueId,
        isConsole = sender !is Player,
        isAdmin = sender.hasPermission("enthusiakoth.admin"),
        canStartBasic = sender.hasPermission("enthusiakoth.start.basic"),
        canStartAdvanced = sender.hasPermission("enthusiakoth.start.advanced"),
        canUseFlare = sender.hasPermission("enthusiakoth.flare.use"),
    )

    private fun sendStartResult(sender: CommandSender, arenaId: String, result: StartResult, fromGui: Boolean) {
        when (result) {
            is StartResult.Started -> sender.sendMessage(
                lang.msg(if (fromGui) "command.success.started_from_gui" else "command.success.started", "arena" to arenaId),
            )
            is StartResult.Rejected -> when (result.failure) {
                StartFailure.NO_PERMISSION -> sender.sendMessage(lang.msg("command.error.no_permission_start"))
                StartFailure.FEATURE_DISABLED -> sender.sendMessage(lang.msg("command.error.manual_start_disabled"))
                StartFailure.LOCKED -> sender.sendMessage(lang.msg("command.error.locked"))
                StartFailure.ALREADY_ACTIVE -> sender.sendMessage(lang.msg("command.error.already_active"))
                StartFailure.ECONOMY_UNAVAILABLE -> sender.sendMessage(lang.msg("command.error.economy_unavailable"))
                StartFailure.INSUFFICIENT_FUNDS -> sender.sendMessage(
                    lang.msg("command.error.insufficient_funds", "cost" to result.cost.toString(), "balance" to (result.balance ?: 0.0).toString()),
                )
                StartFailure.REFUND_FAILED -> sender.sendMessage(lang.msg("command.error.refund_failed"))
                StartFailure.CONCURRENT_REQUEST -> sender.sendMessage(lang.msg("command.error.concurrent_start"))
                StartFailure.INVALID_ARENA -> sender.sendMessage(lang.msg("command.error.koth_not_found_short"))
                else -> sender.sendMessage(lang.msg("command.error.payment_failed"))
            }
        }
    }

    private fun stop(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        if (!kothService.forceEnd()) sender.sendMessage(lang.msg("command.error.no_active"))
        else sender.sendMessage(lang.msg("command.success.ended"))
    }

    private fun giveFlare(sender: CommandSender, playerName: String, arenaId: String, amount: Int) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        val target = Bukkit.getPlayerExact(playerName) ?: run {
            sender.sendMessage(lang.msg("command.error.player_not_found"))
            return
        }
        val arena = arenas()[arenaId] ?: run {
            sender.sendMessage(lang.msg("command.error.koth_not_found_short"))
            return
        }
        val flare = flareService.createFlare(arena).apply { this.amount = amount.coerceAtLeast(1) }
        sender.sendMessage(lang.msg("command.error.flare_given", "player" to target.name, "amount" to flare.amount.toString(), "koth" to arenaId))
        target.sendMessage(lang.msg("command.error.flare_received", "amount" to flare.amount.toString(), "koth" to arenaId))
        target.inventory.addItem(flare).values.forEach { target.world.dropItem(target.location, it) }
    }

    private fun doReload(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        reloadAction()
        sender.sendMessage(lang.msg("command.error.reloaded"))
    }

    private fun status(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        val event = kothService.activeEvent
        val line = lang.msg("command.status.line")
        sender.sendMessage(line)
        sender.sendMessage(lang.msg("command.status.header"))
        sender.sendMessage(lang.msg("command.status.lock", "state" to cfgLoader().locks.state.name.lowercase()))
        sender.sendMessage(lang.msg("command.status.active", "arena" to (event?.arena?.id ?: "None")))
        if (event != null) {
            sender.sendMessage(lang.msg("command.status.capper", "capper" to (kothService.capperName(event) ?: "None")))
            sender.sendMessage(lang.msg("command.status.time_left", "time" to formatTime(event.endsAt.epochSecond - System.currentTimeMillis() / 1000)))
        }
        scheduleService.nextScheduledStart()?.let { next ->
            val seconds = java.time.Duration.between(java.time.Instant.now(), next).toSeconds().coerceAtLeast(0)
            sender.sendMessage(lang.msg("command.status.next_scheduled", "time" to formatTime(seconds)))
        }
        sender.sendMessage(line)
    }

    private fun lock(sender: CommandSender, stateArg: String) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        val state = when (stateArg.lowercase()) {
            "off" -> LockState.UNLOCKED
            "manual" -> LockState.MANUAL_LOCKED
            "all" -> LockState.ALL_LOCKED
            else -> runCatching { LockState.valueOf(stateArg.uppercase()) }.getOrNull()
        }
        if (state == null) {
            sender.sendMessage(lang.msg("command.error.invalid_lock", "states" to LockState.entries.joinToString(", ") { it.name.lowercase() }))
            return
        }
        lockAction(state)
        sender.sendMessage(lang.msg("command.error.lock_set", "state" to state.name.lowercase()))
    }

    private fun privateTest(sender: CommandSender, sub: String, arenaId: String) {
        if (sender !is Player) {
            sender.sendMessage(lang.msg("command.error.not_a_player"))
            return
        }
        when (sub.lowercase()) {
            "start" -> if (sender.hasPermission("enthusiakoth.privatetest")) privateStart(sender, arenaId) else sender.sendMessage(lang.msg("command.error.no_permission"))
            "cancel" -> if (sender.hasPermission("enthusiakoth.privatetest")) privateCancel(sender) else sender.sendMessage(lang.msg("command.error.no_permission"))
            "join" -> privateJoin(sender)
            "leave" -> privateLeave(sender)
            else -> sender.sendMessage(lang.msg("private.usage"))
        }
    }

    private fun privateStart(player: Player, arenaId: String) {
        val arena = arenas()[arenaId] ?: run {
            player.sendMessage(lang.msg("private.error.arena_not_found", "koths" to arenas().keys.joinToString(", ")))
            return
        }
        if (!kothService.startPrivateTest(arena, player.uniqueId, cfgLoader())) {
            player.sendMessage(lang.msg(if (kothService.activeEvent != null) "private.error.already_active" else "private.error.locked"))
        }
    }

    private fun privateJoin(player: Player) {
        val event = kothService.activeEvent ?: run {
            player.sendMessage(lang.msg("private.error.no_active"))
            return
        }
        if (!event.isPrivateTest) {
            player.sendMessage(lang.msg("private.error.not_private"))
            return
        }
        if (event.state !in setOf(EventState.STARTING, EventState.ACTIVE)) {
            player.sendMessage(lang.msg("private.error.expired"))
            return
        }
        if (event.isOwner(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.is_owner"))
            return
        }
        if (!event.join(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.already_joined"))
            return
        }
        player.sendMessage(lang.msg("private.success.joined"))
        if (cfgLoader().privateTesting.showObjectiveParticles) player.sendMessage(lang.msg("private.objective_particles"))
    }

    private fun privateLeave(player: Player) {
        val event = kothService.activeEvent
        if (event == null || !event.isPrivateTest || !event.isParticipant(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.not_in_private"))
            return
        }
        if (event.isOwner(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.owner_cancel"))
            return
        }
        event.leave(player.uniqueId)
        player.sendMessage(lang.msg("private.success.left"))
    }

    private fun privateCancel(player: Player) {
        val event = kothService.activeEvent
        if (event == null || !event.isPrivateTest) {
            player.sendMessage(lang.msg("private.error.no_private_active"))
            return
        }
        if (!event.isOwner(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.is_owner"))
            return
        }
        kothService.forceEnd()
        player.sendMessage(lang.msg("private.success.cancelled"))
    }

    fun handleGuiClick(player: Player, slot: Int, holder: KothGuiHolder) {
        val arenaId = holder.arenaIds.getOrNull(slot) ?: return
        val arena = arenas()[arenaId] ?: return
        val result = startService.start(StartRequest(startActor(player), arena, StartSource.GUI))
        if (result is StartResult.Started) player.closeInventory()
        sendStartResult(player, arenaId, result, true)
    }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val remainder = safe % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }

    private fun formatStatUser(key: String): String {
        val parts = key.split(":", limit = 2)
        if (parts.size != 2) return key
        return when (parts[0]) {
            "solo" -> runCatching { UUID.fromString(parts[1]) }.getOrNull()?.let { Bukkit.getOfflinePlayer(it).name } ?: parts[1].take(8)
            "guild" -> runCatching { UUID.fromString(parts[1]) }.getOrNull()?.let { guilds.guildName(it) } ?: parts[1].take(8)
            else -> parts[1]
        }
    }
}
