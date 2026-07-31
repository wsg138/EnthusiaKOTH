package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.ek.toComponent
import net.badgersmc.ek.toLore
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
    private val flareService: net.badgersmc.ek.application.FlareService,
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
        sender.sendMessage("§7§m-----§r §c§lEnthusiaKOTH §7§m-----".toComponent())
        sender.sendMessage("§a/ekoth gui §7- Open KOTH GUI".toComponent())
        sender.sendMessage("§a/ekoth schedule §7- Show KOTH schedule".toComponent())
        sender.sendMessage("§a/ekoth top [page] §7- KOTH leaderboard".toComponent())
        sender.sendMessage("§a/ekoth stats [player] §7- Your KOTH stats".toComponent())
        if (sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage("§c/ekoth start <arena> §7- Start a KOTH".toComponent())
            sender.sendMessage("§c/ekoth stop §7- Force-end active KOTH".toComponent())
            sender.sendMessage("§c/ekoth cancel §7- Force-end active KOTH".toComponent())
            sender.sendMessage("§c/ekoth giveflare <player> <arena> [amount] §7- Give flare".toComponent())
            sender.sendMessage("§c/ekoth reload §7- Reload config".toComponent())
            sender.sendMessage("§c/ekoth status §7- Show server status".toComponent())
            sender.sendMessage("§c/ekoth lock <unlocked|manual_locked|all_locked> §7- Set lock state".toComponent())
        }
    }

    // -- Player commands --

    private fun gui(sender: CommandSender) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this.".toComponent()); return }
        val arenaIds = arenas().keys.toList()
        val size = ((arenaIds.size + 8) / 9).coerceIn(1, 6) * 9 // rows of 9, cap at 6 rows (54)
        val inventory = Bukkit.createInventory(null, size, "§2§lKOTHs".toComponent())
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
            meta.displayName("§${if (isActive) 'a' else '7'}${id.uppercase()}".toComponent())
            meta.lore(listOf(
                "§8* §fActive: §${if (isActive) 'a' else 'c'}$isActive",
                "§8* §fCapturing: §${if (isActive) 'a' else '7'}$capper",
                "§8* §fTime Left: §${if (isActive) 'a' else '7'}$timeLeft",
            ).toLore())
            item.itemMeta = meta
            inventory.setItem(slot, item)
        }
        sender.openInventory(inventory)
    }

    private fun schedule(sender: CommandSender) {
        val cfg = cfgLoader()
        val now = ZonedDateTime.now(cfg.schedule.zone)
        val timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        sender.sendMessage("§7§m-----§r §a§lKoth Schedule §7§m-----".toComponent())
        sender.sendMessage("§cTime Now: §e$timeStr".toComponent())
        sender.sendMessage("§cTimeZone: §e${cfg.schedule.zone.id}".toComponent())
        sender.sendMessage("".toComponent())
        for ((id, arena) in arenas()) {
            sender.sendMessage("§a$id".toComponent())
            val schedules = arena.schedule.ifEmpty { cfg.schedule.times }
            for (t in schedules) {
                sender.sendMessage("  §8- §a$t".toComponent())
            }
            sender.sendMessage("".toComponent())
        }
    }

    private fun top(sender: CommandSender, page: Int) {
        val cfg = cfgLoader()
        val max = stats.maxPages().coerceAtLeast(1)
        val p = page.coerceIn(1, max)
        sender.sendMessage(cfg.messages.kothTopHeader.replace("&", "§").toComponent())
        var index = (p - 1) * 10 + 1
        for ((key, wins) in stats.allWins().entries.sortedByDescending { it.value }.drop((p - 1) * 10).take(10)) {
            val user = formatStatUser(key)
            sender.sendMessage(cfg.messages.kothTopFormat
                .replace("{INDEX}", index.toString())
                .replace("{USER}", user)
                .replace("{WINS}", wins.toString())
                .replace("{FACTION}", user)
                .replace("&", "§").toComponent())
            index++
        }
    }

    private fun stats(sender: CommandSender, target: String?) {
        val player = if (target != null) Bukkit.getPlayerExact(target) else (sender as? Player)
        if (player == null) { sender.sendMessage("§cPlayer not found.".toComponent()); return }
        val soloKey = "solo:${player.uniqueId}"
        val total = stats.totalWins(soloKey)
        if (sender == player) sender.sendMessage("§aYou have won §e$total §akoths.".toComponent())
        else sender.sendMessage("§e${player.name} §ahas won §e$total §akoths.".toComponent())
    }

    // -- Admin commands --

    private fun start(sender: CommandSender, arenaId: String) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage("§cNo permission.".toComponent()); return }
        val arena = arenas()[arenaId]
        if (arena == null) { sender.sendMessage("§cKOTH doesn't exist! Available: ${arenas().keys.joinToString(", ")}".toComponent()); return }
        if (!kothService.startEvent(arena)) {
            sender.sendMessage(if (kothService.activeEvent != null) "§cA KOTH is already active!".toComponent() else "§cCannot start KOTH — check lock state.".toComponent())
            return
        }
        sender.sendMessage("§aStarted the §e$arenaId §akoth.".toComponent())
    }

    private fun stop(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage("§cNo permission.".toComponent()); return }
        if (!kothService.forceEnd()) { sender.sendMessage("§cNo active KOTH.".toComponent()); return }
        sender.sendMessage("§aEnded the active koth.".toComponent())
    }

    private fun giveFlare(sender: CommandSender, playerName: String, arenaId: String, amount: Int) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage("§cNo permission.".toComponent()); return }
        val target = Bukkit.getPlayerExact(playerName)
        if (target == null) { sender.sendMessage("§cPlayer not found.".toComponent()); return }
        val arena = arenas()[arenaId]
        if (arena == null) { sender.sendMessage("§cKOTH doesn't exist.".toComponent()); return }
        val flare = flareService.createFlare(arena)
        flare.amount = amount.coerceAtLeast(1)
        sender.sendMessage("§aGave §e${target.name} $amount §e${arenaId} §akoth flares.".toComponent())
        target.sendMessage("§aYou received $amount §e${arenaId} §akoth flares!".toComponent())
        target.inventory.addItem(flare).values.forEach { leftover ->
            target.world.dropItem(target.location, leftover)
        }
    }

    private fun doReload(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage("§cNo permission.".toComponent()); return }
        reloadAction()
        sender.sendMessage("§aConfig reloaded.".toComponent())
    }

    // -- Status & Lock commands --

    private fun status(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage("§cNo permission.".toComponent()); return }
        val cfg = cfgLoader()
        val event = kothService.activeEvent
        val line = "§7§m----------------------------------------§r"
        sender.sendMessage(line.toComponent())
        sender.sendMessage("§6§lEnthusiaKOTH Status".toComponent())
        sender.sendMessage("§7Lock: ${cfg.locks.state.name.lowercase()}".toComponent())
        sender.sendMessage("§7Active: ${event?.arena?.id ?: "§7None"}".toComponent())
        if (event != null) {
            val capper = kothService.capperName(event) ?: "None"
            val timeLeft = formatTime((event.endsAt.epochSecond - System.currentTimeMillis() / 1000).coerceAtLeast(0))
            sender.sendMessage("§7  Capper: §a$capper".toComponent())
            sender.sendMessage("§7  Time left: §a$timeLeft".toComponent())
        }
        val next = scheduleService.nextScheduledStart()
        if (next != null) {
            val secs = java.time.Duration.between(java.time.Instant.now(), next).toSeconds().coerceAtLeast(0)
            sender.sendMessage("§7Next scheduled: §a${formatTime(secs)}".toComponent())
        }
        sender.sendMessage(line.toComponent())
    }

    private fun lock(sender: CommandSender, stateArg: String) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage("§cNo permission.".toComponent()); return }
        val normalized = when (stateArg.lowercase()) {
            "off" -> LockState.UNLOCKED
            "manual" -> LockState.MANUAL_LOCKED
            "all" -> LockState.ALL_LOCKED
            else -> try { LockState.valueOf(stateArg.uppercase()) } catch (_: IllegalArgumentException) { null }
        }
        if (normalized == null) {
            sender.sendMessage("§cInvalid lock state. Valid: ${LockState.entries.joinToString(", ") { it.name.lowercase() }}".toComponent())
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
        sender.sendMessage("§aLock set to §e${normalized.name.lowercase()}§a.".toComponent())
    }

    // -- Private test commands --

    private fun privateTest(sender: CommandSender, sub: String, arenaId: String) {
        if (sender !is Player) { sender.sendMessage("§cOnly players can use this.".toComponent()); return }
        when (sub.lowercase()) {
            "start" -> privateStart(sender, arenaId)
            "join" -> privateJoin(sender)
            "leave" -> privateLeave(sender)
            "cancel" -> privateCancel(sender)
            else -> sender.sendMessage("§cUsage: /ekoth private <start|join|leave|cancel> [arena]".toComponent())
        }
    }

    private fun privateStart(player: Player, arenaId: String) {
        val arena = arenas()[arenaId]
        if (arena == null) {
            player.sendMessage("§cArena not found. Available: ${arenas().keys.joinToString(", ")}".toComponent())
            return
        }
        val started = kothService.startPrivateTest(arena, player.uniqueId, cfgLoader())
        if (!started) {
            player.sendMessage(
                if (kothService.activeEvent != null) "§cA KOTH is already active!".toComponent()
                else "§cCannot start private test — check lock state.".toComponent()
            )
        }
    }

    private fun privateJoin(player: Player) {
        val cfg = cfgLoader()
        val event = kothService.activeEvent ?: run {
            player.sendMessage("§cThere is no active private KOTH to join.".toComponent())
            return
        }
        if (!event.isPrivateTest) {
            player.sendMessage("§cThe current KOTH is not a private test.".toComponent())
            return
        }
        if (event.state != net.badgersmc.ek.domain.EventState.STARTING && event.state != net.badgersmc.ek.domain.EventState.ACTIVE) {
            player.sendMessage("§cThis private KOTH is no longer accepting participants.".toComponent())
            return
        }
        if (event.isOwner(player.uniqueId)) {
            player.sendMessage("§cYou are the owner of this private KOTH.".toComponent())
            return
        }
        if (!event.join(player.uniqueId)) {
            player.sendMessage("§cYou are already in this private KOTH.".toComponent())
            return
        }
        player.sendMessage("§aJoined the private KOTH!".toComponent())
        if (cfg.privateTesting.showObjectiveParticles) {
            player.sendMessage("§7Follow the particles to the objective!".toComponent())
        }
    }

    private fun privateLeave(player: Player) {
        val event = kothService.activeEvent ?: run {
            player.sendMessage("§cYou are not in a private KOTH.".toComponent())
            return
        }
        if (!event.isPrivateTest || !event.isParticipant(player.uniqueId)) {
            player.sendMessage("§cYou are not in a private KOTH.".toComponent())
            return
        }
        if (event.isOwner(player.uniqueId)) {
            player.sendMessage("§cThe owner must use /ekoth private cancel instead.".toComponent())
            return
        }
        event.leave(player.uniqueId)
        player.sendMessage("§aLeft the private KOTH.".toComponent())
    }

    private fun privateCancel(player: Player) {
        val event = kothService.activeEvent ?: run {
            player.sendMessage("§cYou do not have an active private KOTH.".toComponent())
            return
        }
        if (!event.isPrivateTest || !event.isOwner(player.uniqueId)) {
            player.sendMessage("§cYou are not the owner of this private KOTH.".toComponent())
            return
        }
        kothService.forceEnd()
        player.sendMessage("§cPrivate KOTH cancelled.".toComponent())
    }

    fun handleGuiClick(player: Player, slot: Int) {
        val arenasList = arenas().keys.toList()
        if (slot < 0 || slot >= arenasList.size) return
        val arenaId = arenasList[slot]
        val arena = arenas()[arenaId] ?: return
        if (kothService.activeEvent != null) {
            player.sendMessage("§cA KOTH is already active!".toComponent())
            return
        }
        if (!player.hasPermission("enthusiakoth.admin")) {
            player.sendMessage("§cYou don't have permission to start KOTHs.".toComponent())
            return
        }
        player.closeInventory()
        kothService.startEvent(arena)
        player.sendMessage("§aStarted §e$arenaId §afrom the GUI.".toComponent())
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
