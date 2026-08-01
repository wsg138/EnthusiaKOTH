package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.FlareService
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.EventState
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class KothCommand(
    private val plugin: org.bukkit.plugin.java.JavaPlugin,
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val kothService: KothService,
    private val scheduleService: ScheduleService,
    private val stats: SqlStatsRepository,
    private val guilds: LumaGuildsAdapter,
    private val flareService: FlareService,
    private val lang: LangService,
    private val arenas: () -> Map<String, KothArena>,
    private val reloadAction: () -> Unit,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) { sendHelp(sender); return true }
        when (args[0].lowercase()) {
            "gui" -> gui(sender)
            "schedule" -> schedule(sender)
            "top" -> top(sender, args.getOrNull(1)?.toIntOrNull() ?: 1)
            "stats" -> stats(sender, args.getOrNull(1))
            "start" -> start(sender, args.getOrNull(1) ?: "")
            "stop" -> stop(sender)
            "cancel" -> stop(sender)
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
            val subs = mutableListOf("gui", "schedule", "top", "stats", "private")
            if (sender.hasPermission("enthusiakoth.admin")) {
                subs.addAll(listOf("start", "stop", "cancel", "giveflare", "reload", "status", "lock"))
            }
            return subs.filter { it.startsWith(args[0].lowercase()) }.toMutableList()
        }
        if (args.size == 2) {
            if (args[0].lowercase() in listOf("start", "giveflare")) {
                return arenas().keys.filter { it.startsWith(args[1].lowercase()) }.toMutableList()
            }
            if (args[0].lowercase() == "private") {
                return listOf("start", "join", "leave", "cancel").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
            }
            if (args[0].lowercase() == "lock") {
                return LockState.entries.map { it.name.lowercase() }.filter { it.startsWith(args[1].lowercase()) }.toMutableList()
            }
        }
        if (args.size == 3 && args[0].lowercase() == "private" && args[1].lowercase() == "start") {
            return arenas().keys.filter { it.startsWith(args[2].lowercase()) }.toMutableList()
        }
        return mutableListOf()
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(lang.msg("command.help.header"))
        sender.sendMessage(lang.msg("command.help.gui"))
        sender.sendMessage(lang.msg("command.help.schedule"))
        sender.sendMessage(lang.msg("command.help.top"))
        sender.sendMessage(lang.msg("command.help.stats"))
        if (sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.help.start"))
            sender.sendMessage(lang.msg("command.help.stop"))
            sender.sendMessage(lang.msg("command.help.cancel"))
            sender.sendMessage(lang.msg("command.help.giveflare"))
            sender.sendMessage(lang.msg("command.help.reload"))
            sender.sendMessage(lang.msg("command.help.status"))
            sender.sendMessage(lang.msg("command.help.lock"))
        }
    }

    // -- Player commands --

    private fun gui(sender: CommandSender) {
        if (sender !is Player) { sender.sendMessage(lang.msg("command.error.not_a_player")); return }
        val arenaIds = arenas().keys.toList()
        val size = ((arenaIds.size + 8) / 9).coerceIn(1, 6) * 9 // rows of 9, cap at 6 rows (54)
        val inventory = Bukkit.createInventory(null, size, lang.msg("command.gui.title"))
        for ((slot, id) in arenaIds.withIndex()) {
            if (slot >= size) break
            val event = kothService.activeEvent
            val isActive = event?.arena?.id == id
            val capper = if (isActive) kothService.capperName(event!!) ?: "None" else "None"
            val timeLeft = if (isActive) {
                val secs = event!!.endsAt.epochSecond - System.currentTimeMillis() / 1000
                formatTime(secs.coerceAtLeast(0))
            } else { "Scheduled" }
            val item = ItemStack(if (isActive) Material.GLOWSTONE_DUST else Material.REDSTONE_TORCH)
            val meta = item.itemMeta ?: continue
            meta.displayName(lang.msg(if (isActive) "command.gui.item_name_active" else "command.gui.item_name_inactive", "name" to id.uppercase()))
            meta.lore(listOf(
                lang.msg(if (isActive) "command.gui.lore_active_yes" else "command.gui.lore_active_no", "status" to isActive.toString()),
                lang.msg(if (isActive) "command.gui.lore_capturing_yes" else "command.gui.lore_capturing_no", "capper" to capper),
                lang.msg(if (isActive) "command.gui.lore_time_left_yes" else "command.gui.lore_time_left_no", "time" to timeLeft),
            ))
            item.itemMeta = meta
            inventory.setItem(slot, item)
        }
        sender.openInventory(inventory)
    }

    private fun schedule(sender: CommandSender) {
        val cfg = cfgLoader()
        val now = ZonedDateTime.now(cfg.schedule.zone)
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        sender.sendMessage(lang.msg("command.schedule.header"))
        sender.sendMessage(lang.msg("command.schedule.time_now", "time" to timeStr))
        sender.sendMessage(lang.msg("command.schedule.timezone", "zone" to cfg.schedule.zone.id))
        sender.sendMessage(Component.empty())
        for ((id, arena) in arenas()) {
            sender.sendMessage(lang.msg("command.schedule.entry", "name" to id))
            val schedules = arena.schedule.ifEmpty { cfg.schedule.times }
            for (t in schedules) {
                sender.sendMessage(lang.msg("command.schedule.entry_time", "time" to t))
            }
            sender.sendMessage(Component.empty())
        }
    }

    private fun top(sender: CommandSender, page: Int) {
        val cfg = cfgLoader()
        val max = stats.maxPages().coerceAtLeast(1)
        val p = page.coerceIn(1, max)
        sender.sendMessage(lang.msg("command.top.header"))
        var index = (p - 1) * 10 + 1
        for ((key, wins) in stats.allWins().entries.sortedByDescending { it.value }.drop((p - 1) * 10).take(10)) {
            val user = formatStatUser(key)
            sender.sendMessage(lang.msg("command.top.entry", "rank" to index.toString(), "player" to user, "wins" to wins.toString()))
            index++
        }
    }

    private fun stats(sender: CommandSender, target: String?) {
        val player = if (target != null) Bukkit.getPlayerExact(target) else (sender as? Player)
        if (player == null) { sender.sendMessage(lang.msg("command.error.player_not_found")); return }
        val soloKey = "solo:${player.uniqueId}"
        val total = stats.totalWins(soloKey)
        if (sender == player) sender.sendMessage(lang.msg("command.error.stats_self", "wins" to total.toString()))
        else sender.sendMessage(lang.msg("command.error.stats_other", "player" to player.name, "wins" to total.toString()))
    }

    // -- Admin commands --

    private fun start(sender: CommandSender, arenaId: String) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        val arena = arenas()[arenaId]
        if (arena == null) { sender.sendMessage(lang.msg("command.error.koth_not_found", "koths" to arenas().keys.joinToString(", "))); return }

        val cfg = cfgLoader()
        val cost = cfg.manualStart.basicCost

        // Paid starts: charge the starter's guild bank (LumaGuilds) before starting.
        var paidByGuild: java.util.UUID? = null
        if (cost > 0.0) {
            if (sender !is Player) {
                sender.sendMessage(lang.msg("command.error.not_a_player"))
                return
            }
            val guildId = guilds.playerGuildId(sender)
            if (guildId == null) {
                sender.sendMessage(lang.msg("command.error.no_guild"))
                return
            }
            val balance = guilds.getBalance(guildId)
            if (balance < cost) {
                sender.sendMessage(lang.msg("command.error.insufficient_funds", "cost" to cost.toString(), "balance" to balance.toString()))
                return
            }
            if (!guilds.withdrawFromVault(guildId, cost, "KOTH start")) {
                sender.sendMessage(lang.msg("command.error.payment_failed"))
                return
            }
            paidByGuild = guildId
        }

        if (!kothService.startEvent(arena, paidByGuild = paidByGuild, paidCost = cost)) {
            // Refund if the start failed after charging (e.g. race with another start)
            if (paidByGuild != null && cost > 0.0) {
                guilds.depositToVault(paidByGuild, cost, "KOTH start refund")
            }
            sender.sendMessage(lang.msg(if (kothService.activeEvent != null) "command.error.already_active" else "command.error.locked"))
            return
        }
        sender.sendMessage(lang.msg("command.success.started", "arena" to arenaId))
    }

    private fun stop(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        if (!kothService.forceEnd()) { sender.sendMessage(lang.msg("command.error.no_active")); return }
        sender.sendMessage(lang.msg("command.success.ended"))
    }

    private fun giveFlare(sender: CommandSender, playerName: String, arenaId: String, amount: Int) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        val target = Bukkit.getPlayerExact(playerName)
        if (target == null) { sender.sendMessage(lang.msg("command.error.player_not_found")); return }
        val arena = arenas()[arenaId]
        if (arena == null) { sender.sendMessage(lang.msg("command.error.koth_not_found_short")); return }
        val flare = flareService.createFlare(arena)
        flare.amount = amount.coerceAtLeast(1)
        sender.sendMessage(lang.msg("command.error.flare_given", "player" to target.name, "amount" to amount.toString(), "koth" to arenaId))
        target.sendMessage(lang.msg("command.error.flare_received", "amount" to amount.toString(), "koth" to arenaId))
        target.inventory.addItem(flare).values.forEach { leftover ->
            target.world.dropItem(target.location, leftover)
        }
    }

    private fun doReload(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        reloadAction()
        sender.sendMessage(lang.msg("command.error.reloaded"))
    }

    // -- Status & Lock commands --

    private fun status(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        val cfg = cfgLoader()
        val event = kothService.activeEvent
        val line = lang.msg("command.status.line")
        sender.sendMessage(line)
        sender.sendMessage(lang.msg("command.status.header"))
        sender.sendMessage(lang.msg("command.status.lock", "state" to cfg.locks.state.name.lowercase()))
        sender.sendMessage(lang.msg("command.status.active", "arena" to (event?.arena?.id ?: "None")))
        if (event != null) {
            val capper = kothService.capperName(event) ?: "None"
            val timeLeft = formatTime((event.endsAt.epochSecond - System.currentTimeMillis() / 1000).coerceAtLeast(0))
            sender.sendMessage(lang.msg("command.status.capper", "capper" to capper))
            sender.sendMessage(lang.msg("command.status.time_left", "time" to timeLeft))
        }
        val next = scheduleService.nextScheduledStart()
        if (next != null) {
            val secs = java.time.Duration.between(java.time.Instant.now(), next).toSeconds().coerceAtLeast(0)
            sender.sendMessage(lang.msg("command.status.next_scheduled", "time" to formatTime(secs)))
        }
        sender.sendMessage(line)
    }

    private fun lock(sender: CommandSender, stateArg: String) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        val normalized = when (stateArg.lowercase()) {
            "off" -> LockState.UNLOCKED
            "manual" -> LockState.MANUAL_LOCKED
            "all" -> LockState.ALL_LOCKED
            else -> try { LockState.valueOf(stateArg.uppercase()) } catch (_: IllegalArgumentException) { null }
        }
        if (normalized == null) {
            sender.sendMessage(lang.msg("command.error.invalid_lock", "states" to LockState.entries.joinToString(", ") { it.name.lowercase() }))
            return
        }
        val cfg = cfgLoader() // unused — lock state written below, applied by reloadAction
        // Write to Bukkit config and save to disk
        try {
            plugin.config.set("locks.state", normalized.name)
            plugin.saveConfig()
        } catch (_: Exception) { /* best-effort */ }
        // Apply to live config via reload
        reloadAction()
        sender.sendMessage(lang.msg("command.error.lock_set", "state" to normalized.name.lowercase()))
    }

    // -- Private test commands --

    private fun privateTest(sender: CommandSender, sub: String, arenaId: String) {
        if (sender !is Player) { sender.sendMessage(lang.msg("command.error.not_a_player")); return }
        when (sub.lowercase()) {
            // Starting/cancelling occupies the single active-event slot and is
            // the privileged action — require the dedicated permission.
            "start" -> {
                if (!sender.hasPermission("enthusiakoth.privatetest")) {
                    sender.sendMessage(lang.msg("command.error.no_permission"))
                    return
                }
                privateStart(sender, arenaId)
            }
            "cancel" -> {
                if (!sender.hasPermission("enthusiakoth.privatetest")) {
                    sender.sendMessage(lang.msg("command.error.no_permission"))
                    return
                }
                privateCancel(sender)
            }
            // Joining/leaving an existing private test is a player action and
            // must NOT require the admin start permission.
            "join" -> privateJoin(sender)
            "leave" -> privateLeave(sender)
            else -> sender.sendMessage(lang.msg("private.usage"))
        }
    }

    private fun privateStart(player: Player, arenaId: String) {
        val arena = arenas()[arenaId]
        if (arena == null) {
            player.sendMessage(lang.msg("private.error.arena_not_found", "koths" to arenas().keys.joinToString(", ")))
            return
        }
        val started = kothService.startPrivateTest(arena, player.uniqueId, cfgLoader())
        if (!started) {
            player.sendMessage(
                lang.msg(if (kothService.activeEvent != null) "private.error.already_active" else "private.error.locked")
            )
        }
    }

    private fun privateJoin(player: Player) {
        val cfg = cfgLoader()
        val event = kothService.activeEvent ?: run {
            player.sendMessage(lang.msg("private.error.no_active"))
            return
        }
        if (!event.isPrivateTest) {
            player.sendMessage(lang.msg("private.error.not_private"))
            return
        }
        if (event.state != net.badgersmc.ek.domain.EventState.STARTING && event.state != net.badgersmc.ek.domain.EventState.ACTIVE) {
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
        if (cfg.privateTesting.showObjectiveParticles) {
            player.sendMessage(lang.msg("private.objective_particles"))
        }
    }

    private fun privateLeave(player: Player) {
        val event = kothService.activeEvent ?: run {
            player.sendMessage(lang.msg("private.error.not_in_private"))
            return
        }
        if (!event.isPrivateTest || !event.isParticipant(player.uniqueId)) {
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
        val event = kothService.activeEvent ?: run {
            player.sendMessage(lang.msg("private.error.no_private_active"))
            return
        }
        if (!event.isPrivateTest || !event.isOwner(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.is_owner"))
            return
        }
        kothService.forceEnd()
        player.sendMessage(lang.msg("private.error.cancelled_active"))
    }

    fun handleGuiClick(player: Player, slot: Int) {
        val arenasList = arenas().keys.toList()
        if (slot < 0 || slot >= arenasList.size) return
        val arenaId = arenasList[slot]
        val arena = arenas()[arenaId] ?: return
        if (kothService.activeEvent != null) {
            player.sendMessage(lang.msg("command.error.already_active"))
            return
        }
        if (!player.hasPermission("enthusiakoth.admin")) {
            player.sendMessage(lang.msg("command.error.no_permission_start"))
            return
        }
        player.closeInventory()
        kothService.startEvent(arena)
        player.sendMessage(lang.msg("command.success.started_from_gui", "arena" to arenaId))
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60; val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun formatStatUser(key: String): String {
        val parts = key.split(":", limit = 2)
        if (parts.size != 2) return key
        return when (parts[0]) {
            "solo" -> kotlin.runCatching { java.util.UUID.fromString(parts[1]) }.getOrNull()
                ?.let { Bukkit.getOfflinePlayer(it).name } ?: parts[1].take(8)
            "guild" -> kotlin.runCatching { java.util.UUID.fromString(parts[1]) }.getOrNull()
                ?.let { guilds.guildName(it) ?: parts[1].take(8) } ?: parts[1]
            else -> parts[1]
        }
    }
}
