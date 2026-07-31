package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.ProgressBarConfig
import net.badgersmc.ek.config.ReminderConfig
import net.badgersmc.ek.domain.*
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.ek.toComponent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import java.time.Instant
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A KOTH event queued to start when the current event finishes.
 */
data class QueuedEvent(
    val arena: KothArena,
    val startSource: EventKind,
    val scheduledAt: Instant,
)

/**
 * Core KOTH game loop. Runs every second (20 ticks).
 * Inspired by FactionsKore's KothFeature#runKoth() tick logic.
 */
class KothService(
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val stats: SqlStatsRepository,
    private val guilds: LumaGuildsAdapter,
    private val displayService: DisplayService,
    private val fireworkService: FireworkCelebrationService,
    private val discordWebhook: DiscordWebhookService,
    private val zoneBorderService: ZoneBorderService,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) {
    @Volatile var activeEvent: KothEvent? = null
    private val reminderCounters = mutableMapOf<String, Int>()
    private val eventQueue = mutableListOf<QueuedEvent>()
    private var discordLastUpdate: Long? = null

    fun tick() {
        val event = activeEvent ?: return
        val now = Instant.now()
        val cfg = cfgLoader()

        when (event.state) {
            EventState.STARTING -> {
                if (!now.isBefore(event.startsAt)) {
                    event.state = EventState.ACTIVE
                    broadcast(cfg.messages.beginMessage, "KOTH_NAME" to event.arena.id, "LOCATION" to locString(event.arena.zone))
                }
            }
            EventState.ACTIVE -> {
                tickActive(event, cfg)
            }
            else -> {}
        }
    }

    private fun tickActive(event: KothEvent, cfg: EnthusiaKothConfig) {
        val arena = event.arena
        val now = Instant.now()

        // Timeout check
        if (!now.isBefore(event.endsAt)) {
            finishEvent(event, cfg, resolveWinner(event))
            return
        }

        // Get players in zone (CAPTURE/CONQUEST) or near moving point (MOVING)
        val playersInZone = when (arena.family.lowercase()) {
            "moving" -> playersNearMovingPoint(event)
            else -> playersInCuboid(event)
        }

        // Empty zone early-end for CAPTURE family
        if (arena.family.lowercase() == "capture" && playersInZone.isEmpty()
            && !event.scores.keys.any { (event.scores[it] ?: 0.0) > 0 }) {
            if (event.currentController == null && !now.isBefore(event.endsAt)) {
                finishEvent(event, cfg, resolveWinner(event))
                return
            }
        }

        // Progress bar for players in zone
        if (cfg.progressBar.enabled && event.currentController != null) {
            val progress = progressBar(event, cfg.progressBar)
            playersInZone.forEach { p ->
                p.sendActionBar(progress.toComponent())
            }
        }

        // Reminder
        val rem = cfg.reminders
        if (rem.enabled) {
            val counter = reminderCounters.getOrPut(event.arena.id) { rem.intervalSeconds }
            if (counter <= 0) {
                reminderCounters[event.arena.id] = rem.intervalSeconds
                val capper = capperName(event)
                broadcast(rem.format, "KOTH" to event.arena.id, "CAPPER" to (capper ?: "None"),
                    "TIME_LEFT" to formatTime(event.endsAt.epochSecond - now.epochSecond),
                    "LOCATION" to locString(event.arena.zone))
            } else {
                reminderCounters[event.arena.id] = counter - 1
            }
        }

        // Resolve teams
        val teamsInZone = resolveTeams(playersInZone, arena)

        // Family-specific capture logic
        when (arena.family.lowercase()) {
            "conquest" -> tickConquest(event, arena, teamsInZone)
            else -> tickCaptureFamily(event, arena, cfg, teamsInZone)
        }

        // Bossbar display
        val capperName = capperName(event)
        val timeLeft = formatTime(event.endsAt.epochSecond - now.epochSecond)
        val contested = teamsInZone.size > 1
        displayService.showKoth(arena.id, capperName, timeLeft, contested)

        // Discord live update
        if (cfg.discord.enabled && cfg.discord.liveUpdateSeconds > 0) {
            val lastUpdate = discordLastUpdate
            val interval = cfg.discord.liveUpdateSeconds
            if (lastUpdate == null || (now.epochSecond - lastUpdate) >= interval) {
                discordLastUpdate = now.epochSecond
                val capper = event.currentController
                val contested = teamsInZone.size > 1
                val timeLeft = formatTime(event.endsAt.epochSecond - now.epochSecond)
                discordWebhook.sendLiveUpdate(event.arena.id, capper, contested, timeLeft)
            }
        }
    }

    /** CAPTURE / MOVING family tick — single controller, capture threshold */
    private fun tickCaptureFamily(event: KothEvent, arena: KothArena, cfg: EnthusiaKothConfig, teamsInZone: List<TeamId>) {
        if (event.currentController == null) {
            if (teamsInZone.size == 1) {
                val team = teamsInZone.first()
                event.currentController = team
                val name = teamName(team)
                broadcast(cfg.messages.enterMessage,
                    "ENTERED" to name, "KOTH_NAME" to arena.id,
                    "CAP_TIME" to formatTime(arena.captureSeconds.toLong()))
            }
        } else {
            val controller = event.currentController!!
            if (controller !in teamsInZone) {
                performLeave(event, cfg)
            } else if (arena.contestWhenMultipleCappers && teamsInZone.size > 1) {
                performLeave(event, cfg)
            } else {
                tickCaptureProgress(event, arena, cfg)
            }
        }
    }

    /** CONQUEST family tick — score accumulation with speed bonuses per capper count */
    private fun tickConquest(event: KothEvent, arena: KothArena, teamsInZone: List<TeamId>) {
        if (teamsInZone.size == 1) {
            val team = teamsInZone.first()
            event.currentController = team
            // Count how many players from the controlling team are in the zone for speed bonuses
            val capperCount = Bukkit.getOnlinePlayers().count { p ->
                p.isValid && !p.isDead && p.gameMode != GameMode.SPECTATOR
                        && arena.zone.contains(p.location)
                        && (if (arena.ignoreFactions) p.uniqueId
                            else guilds.playerGuildId(p))?.let { pid ->
                    if (arena.ignoreFactions) TeamId(TeamMode.SOLO, pid) == team
                    else TeamId(TeamMode.GUILD, pid) == team
                } == true
            }
            val multiplier = arena.captureSpeedBonuses[capperCount] ?: 1.0
            event.addScore(team, multiplier)
        } else {
            event.currentController = null
        }
    }

    /** Score tick for CAPTURE/MOVING — increment toward captureSeconds */
    private fun tickCaptureProgress(event: KothEvent, arena: KothArena, cfg: EnthusiaKothConfig) {
        val controller = event.currentController ?: return
        val current = event.scores[controller] ?: 0.0
        val next = current + 1.0
        event.scores[controller] = min(arena.captureSeconds.toDouble(), next)

        val secsLeft = arena.captureSeconds - next.toInt()
        if (secsLeft > 0 && secsLeft % 30 == 0) {
            val name = teamName(controller)
            broadcast(cfg.messages.cappingMessage,
                "CAPPING" to name, "KOTH_NAME" to arena.id,
                "TIME_LEFT" to formatTime(secsLeft.toLong()),
                "LOCATION" to locString(arena.zone))
        }

        if (next >= arena.captureSeconds) {
            finishEvent(event, cfgLoader(), controller)
        }
    }

    /** Players within the cuboid zone (CAPTURE / CONQUEST) */
    private fun playersInCuboid(event: KothEvent): List<Player> {
        val arena = event.arena
        return Bukkit.getOnlinePlayers().filter { p ->
            p.isValid && !p.isDead && p.gameMode != GameMode.SPECTATOR
                    && arena.zone.contains(p.location)
        }
    }

    /** Players near the moving capture point (MOVING) */
    private fun playersNearMovingPoint(event: KothEvent): List<Player> {
        val arena = event.arena
        val point = calculateMovingPoint(event)
        event.movingPoint = point
        val world = Bukkit.getWorld(arena.zone.worldName) ?: return emptyList()
        val radiusSq = arena.zone.radiusSq
        val (px, _, pz) = point
        return Bukkit.getOnlinePlayers().filter { p ->
            p.isValid && !p.isDead && p.gameMode != GameMode.SPECTATOR
                    && p.world.name == arena.zone.worldName
                    && p.location.distanceSquared(org.bukkit.Location(world, px, p.location.y, pz)) <= radiusSq
        }
    }

    /** Calculate the moving point along a square path */
    private fun calculateMovingPoint(event: KothEvent): Triple<Double, Double, Double> {
        val arena = event.arena
        val world = Bukkit.getWorld(arena.zone.worldName)
        if (world == null) return Triple(0.0, 80.0, 0.0)
        val centerLoc = event.arena.zone.center(world)
        val half = arena.movingSquareSize / 2.0
        val perimeter = arena.movingSquareSize * 4.0
        val elapsed = (System.currentTimeMillis() - event.startsAt.toEpochMilli()).coerceAtLeast(0) / 1000.0
        val distance = (elapsed * arena.movingSpeedBlocksPerSecond) % perimeter

        val cx = centerLoc.x
        val cz = centerLoc.z
        val (px, pz) = when {
            distance <= half -> cx to (cz - distance)
            distance <= half + arena.movingSquareSize -> (cx + distance - half) to (cz - half)
            distance <= half + arena.movingSquareSize * 2.0 -> (cx + half) to (cz - half + (distance - half - arena.movingSquareSize))
            distance <= half + arena.movingSquareSize * 3.0 -> (cx + half - (distance - half - arena.movingSquareSize * 2.0)) to (cz + half)
            else -> (cx - (distance - half - arena.movingSquareSize * 3.0)) to (cz + half)
        }
        return Triple(px, centerLoc.y, pz)
    }

    /** Determines winner based on family rules */
    private fun resolveWinner(event: KothEvent): TeamId? {
        val arena = event.arena
        return when (arena.family.lowercase()) {
            "conquest" -> {
                // Highest score wins; no capture threshold
                val (winner, score) = event.scores.maxByOrNull { it.value } ?: return null
                if (score <= 0.0) return null
                val tied = event.scores.count { it.value == score }
                if (tied > 1) return null
                winner
            }
            else -> {
                // CAPTURE / MOVING — controller who reached capture threshold
                event.currentController
            }
        }
    }

    private fun performLeave(event: KothEvent, cfg: EnthusiaKothConfig) {
        val controller = event.currentController ?: return
        val name = teamName(controller)
        broadcast(cfg.messages.leaveMessage,
            "LEFT" to name, "KOTH_NAME" to event.arena.id,
            "TIME_LEFT" to formatTime(event.arena.captureSeconds.toLong()))

        val arena = event.arena
        when (arena.leaveBehavior) {
            CaptureLeaveBehavior.RESET -> {
                event.scores.clear()
                event.currentController = null
            }
            CaptureLeaveBehavior.DECAY -> {
                val current = event.scores[controller] ?: 0.0
                val decayed = (current - arena.decayPerSecond).coerceAtLeast(0.0)
                if (decayed <= 0.0) {
                    event.scores.remove(controller)
                    event.currentController = null
                } else {
                    event.scores[controller] = decayed
                }
            }
            CaptureLeaveBehavior.PAUSE -> {
                // Freeze progress — don't reset or decay, keep the score
                event.currentController = null
            }
        }
    }

    private fun finishEvent(event: KothEvent, cfg: EnthusiaKothConfig, winner: TeamId?) {
        event.state = EventState.COMPLETED
        val msg = cfg.messages

        if (winner != null) {
            val name = teamName(winner)
            broadcast(msg.captureMessage, "CAPTURED" to name, "KOTH_NAME" to event.arena.id)

            // Track win
            val statKey = if (winner.mode == TeamMode.GUILD) {
                "guild:${winner.id}"
            } else {
                "solo:${winner.id}"
            }
            stats.incrementWin(statKey, event.arena.id)
            stats.save()

            // Execute reward commands (FactionsKore style)
            executeRewards(event, winner)
        }

        // Capture wasContested BEFORE clearing scores
        val wasContested = event.scores.size > 1
        val captured = event.currentController

        event.clearScores()
        event.currentController = null
        activeEvent = null
        reminderCounters.remove(event.arena.id)
        discordLastUpdate = null
        displayService.clear()
        zoneBorderService.hide()

        // Discord capture announcement + fireworks
        if (winner != null) {
            discordWebhook.sendCapture(event.arena.id, winner, wasContested)
            fireworkService.celebrate(event.arena.zone)
        }

        // Check for queued events
        processQueue()
    }

    private fun executeRewards(event: KothEvent, winner: TeamId) {
        val arena = event.arena
        val name = teamName(winner)
        val guildId = if (winner.mode == TeamMode.GUILD) winner.id else null

        for (cmd in arena.rewards) {
            if (executeBankReward(cmd, guildId)) continue // bank deposit — no console command
            val resolved = cmd
                .replace("{PLAYER}", name)
                .replace("{FACTION}", name)
                .replace("{KOTH}", event.arena.id)
            if (cmd.contains("{ALL_ONLINE}") && guildId != null) {
                for (member in guilds.onlineMembers(guildId)) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        resolved.replace("{ALL_ONLINE}", member.name))
                }
            } else if (cmd.contains("{ALL_ONLINE}")) {
                Bukkit.getOnlinePlayers().forEach { p ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        resolved.replace("{ALL_ONLINE}", p.name))
                }
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)
            }
        }

        for ((cmd, chance) in arena.chancedRewards) {
            if (ThreadLocalRandom.current().nextDouble() * 100.0 >= chance) continue
            if (executeBankReward(cmd, guildId)) continue
            val resolved = cmd
                .replace("{PLAYER}", name)
                .replace("{FACTION}", name)
                .replace("{KOTH}", event.arena.id)
            if (cmd.contains("{ALL_ONLINE}") && guildId != null) {
                for (member in guilds.onlineMembers(guildId)) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        resolved.replace("{ALL_ONLINE}", member.name))
                }
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)
            }
        }
    }

    /**
     * Handles [bank N] reward entry.
     * Deposits directly to the guild vault via GuildLookup.bankDeposit(),
     * bypassing Vault/console commands entirely.
     *
     * Syntax:
     *   bank 500 — deposit 500 to the winning guild's bank
     */
    private fun executeBankReward(cmd: String, guildId: UUID?): Boolean {
        if (!cmd.startsWith("bank", ignoreCase = true)) return false
        if (guildId == null) return true // solo winners can't get guild bank rewards

        val parts = cmd.split("\\s+".toRegex())
        if (parts.size < 2) return true

        val amount = parts[1].toLongOrNull() ?: return true
        guilds.depositToVault(guildId, amount.toDouble(), "KOTH guild reward")
        return true
    }

    fun startEvent(arena: KothArena, durationOverride: Int? = null, kind: EventKind = EventKind.STANDARD): Boolean {
        if (activeEvent != null) return false
        val cfg = cfgLoader()
        if (!cfg.locks.state.allows(kind)) return false
        val now = Instant.now()
        val duration = durationOverride ?: arena.durationSeconds
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = arena,
            startsAt = now,
            endsAt = now.plusSeconds(duration.toLong()),
            state = EventState.ACTIVE,
        )
        activeEvent = event
        broadcast(cfg.messages.beginMessage, "KOTH_NAME" to arena.id, "LOCATION" to locString(arena.zone))
        discordWebhook.sendStart(arena.id, locString(arena.zone))
        if (cfg.display.zoneBorder) zoneBorderService.show(arena.zone)
        return true
    }

    /**
     * Queue an event to start when the current one finishes.
     * If no event is active, starts immediately.
     */
    fun queueStart(arena: KothArena, kind: EventKind = EventKind.STANDARD): Boolean {
        if (activeEvent == null) {
            return startEvent(arena, kind = kind)
        }
        eventQueue.add(QueuedEvent(arena, kind, Instant.now()))
        return true
    }

    /**
     * Process the next queued event, if any.
     * Called automatically at the end of [finishEvent].
     */
    fun processQueue() {
        val next = eventQueue.removeFirstOrNull() ?: return
        startEvent(next.arena, kind = next.startSource)
    }

    /** Returns the next queued event without removing it, or null if the queue is empty. */
    fun nextQueued(): QueuedEvent? = eventQueue.firstOrNull()

    /** Returns a snapshot of all currently queued events. */
    fun queuedEvents(): List<QueuedEvent> = eventQueue.toList()

    /** Start a private test KOTH with lobby and quick timing */
    fun startPrivateTest(arena: KothArena, ownerId: UUID, cfg: EnthusiaKothConfig): Boolean {
        if (activeEvent != null) return false
        if (!cfg.locks.state.allows(EventKind.PRIVATE_TEST)) return false
        val testing = cfg.privateTesting
        val now = Instant.now()
        val lobbySecs = testing.lobbySeconds.coerceAtLeast(0)
        val duration = if (testing.quickMatchDurationSeconds > 0) testing.quickMatchDurationSeconds else arena.durationSeconds
        val captureSecs = if (testing.quickCaptureSeconds > 0) testing.quickCaptureSeconds else arena.captureSeconds

        val quickArena = arena.copy(
            durationSeconds = duration,
            captureSeconds = captureSecs,
        )
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = quickArena,
            startsAt = now.plusSeconds(lobbySecs.toLong()),
            endsAt = now.plusSeconds((lobbySecs + duration).toLong()),
            state = if (lobbySecs > 0) EventState.STARTING else EventState.ACTIVE,
            owner = ownerId,
            isPrivateTest = true,
            lobbySeconds = lobbySecs,
        )
        // Auto-join the owner
        event.join(ownerId)
        activeEvent = event

        if (lobbySecs > 0) {
            val p = Bukkit.getPlayer(ownerId)
            p?.sendMessage(lang.msg("private.lobby_open", "seconds" to lobbySecs.toString()))
        } else {
            val p = Bukkit.getPlayer(ownerId)
            p?.sendMessage(lang.msg("private.started", "duration" to duration.toString(), "capture" to captureSecs.toString()))
            if (testing.showObjectiveParticles) {
                p?.sendMessage(lang.msg("private.objective_particles"))
            }
        }
        return true
    }

    fun forceEnd(): Boolean {
        val event = activeEvent ?: return false
        val cfg = cfgLoader()
        broadcast(cfg.messages.forcefullyEnded, "KOTH" to event.arena.id)
        event.state = EventState.CANCELLED
        event.clearScores()
        event.currentController = null
        activeEvent = null
        reminderCounters.remove(event.arena.id)
        displayService.clear()
        zoneBorderService.hide()
        return true
    }

    private fun resolveTeams(players: List<Player>, arena: KothArena): List<TeamId> {
        val teams = mutableSetOf<TeamId>()
        if (arena.ignoreFactions) {
            // Skip guild resolution — everyone caps solo
            players.forEach { p -> teams.add(TeamId(TeamMode.SOLO, p.uniqueId)) }
        } else {
            // Players in a guild cap for their guild; unaffiliated players cap solo
            players.forEach { p ->
                val guildId = guilds.playerGuildId(p)
                if (guildId != null) {
                    teams.add(TeamId(TeamMode.GUILD, guildId))
                } else {
                    teams.add(TeamId(TeamMode.SOLO, p.uniqueId))
                }
            }
        }
        return teams.toList()
    }

    private fun teamName(team: TeamId): String {
        return if (team.mode == TeamMode.GUILD) {
            guilds.guildName(team.id) ?: team.id.toString().take(8)
        } else {
            Bukkit.getOfflinePlayer(team.id).name ?: team.id.toString().take(8)
        }
    }

    fun capperName(event: KothEvent): String? {
        val ctl = event.currentController ?: return null
        return teamName(ctl)
    }

    private fun locString(zone: CaptureZone): String {
        val world = Bukkit.getWorld(zone.worldName) ?: return "?,?,?"
        val c = zone.center(world)
        return "${c.blockX}, ${c.blockY}, ${c.blockZ}"
    }

    private fun progressBar(event: KothEvent, cfg: ProgressBarConfig): String {
        val arena = event.arena
        val isConquest = arena.family.equals("conquest", ignoreCase = true)
        val current = event.scores.values.maxOrNull() ?: 0.0
        val max = if (isConquest) event.scores.values.sum().coerceAtLeast(1.0) else arena.captureSeconds.toDouble()
        val progress = (current / max).coerceIn(0.0, 1.0)
        val filled = (progress * cfg.length).toInt()
        val empty = cfg.length - filled
        val bar = cfg.character.repeat(filled) + "&7".repeat(empty.coerceAtLeast(0))
        return cfg.format.replace("{PROGRESS_BAR}", bar)
    }

    private fun broadcast(msg: String, vararg pairs: Pair<String, String>) {
        var resolved = msg
        for ((key, value) in pairs) {
            resolved = resolved.replace("{$key}", value)
        }
        val component = resolved.replace("&", "§").toComponent()
        Bukkit.broadcast(component)
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    fun shutdown() {
        forceEnd()
    }
}
