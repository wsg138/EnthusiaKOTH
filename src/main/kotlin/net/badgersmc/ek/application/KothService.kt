package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.ProgressBarConfig
import net.badgersmc.ek.domain.CaptureLeaveBehavior
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.EventState
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.ek.toComponent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min

data class QueuedEvent(
    val arena: KothArena,
    val startSource: EventKind,
    val scheduledAt: Instant,
)

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
    companion object {
        internal fun movingPointAt(
            elapsedSeconds: Double,
            squareSize: Double,
            speedBlocksPerSecond: Double,
            centerX: Double,
            centerZ: Double,
        ): Pair<Double, Double> {
            val size = squareSize.coerceAtLeast(0.1)
            val half = size / 2.0
            val perimeter = size * 4.0
            val distance = (elapsedSeconds.coerceAtLeast(0.0) * speedBlocksPerSecond) % perimeter
            return when {
                distance < size -> (centerX - half + distance) to (centerZ - half)
                distance < size * 2.0 -> (centerX + half) to (centerZ - half + distance - size)
                distance < size * 3.0 -> (centerX + half - (distance - size * 2.0)) to (centerZ + half)
                else -> (centerX - half) to (centerZ + half - (distance - size * 3.0))
            }
        }
    }

    @Volatile var activeEvent: KothEvent? = null
    private val reminderCounters = mutableMapOf<String, Int>()
    private val eventQueue = mutableListOf<QueuedEvent>()
    private var discordLastUpdate: Long? = null

    fun tick() {
        val event = activeEvent ?: return
        val now = Instant.now()
        val cfg = cfgLoader()
        when (event.state) {
            EventState.STARTING -> if (!now.isBefore(event.startsAt)) activateEvent(event, cfg)
            EventState.ACTIVE -> tickActive(event, cfg)
            else -> Unit
        }
    }

    private fun activateEvent(event: KothEvent, cfg: EnthusiaKothConfig) {
        event.state = EventState.ACTIVE
        sendEventMessage(
            event,
            lang.msg("koth.begin", "koth_name" to event.arena.id, "location" to locString(event.arena.zone)),
        )
        if (!event.isPrivateTest) {
            discordWebhook.sendStart(event.arena.id, locString(event.arena.zone))
            if (cfg.display.zoneBorder) zoneBorderService.show(event.arena.zone)
        }
    }

    private fun tickActive(event: KothEvent, cfg: EnthusiaKothConfig) {
        val arena = event.arena
        val now = Instant.now()
        if (!now.isBefore(event.endsAt)) {
            finishEvent(event, resolveWinner(event))
            return
        }
        val playersInZone = if (arena.family.equals("moving", true)) playersNearMovingPoint(event) else playersInCuboid(event)

        if (cfg.progressBar.enabled && event.currentController != null) {
            val progress = progressBar(event, cfg.progressBar)
            playersInZone.forEach { it.sendActionBar(progress) }
        }

        val reminder = cfg.reminders
        if (reminder.enabled) {
            val counter = reminderCounters.getOrPut(event.arena.id) { reminder.intervalSeconds }
            if (counter <= 0) {
                reminderCounters[event.arena.id] = reminder.intervalSeconds
                sendEventMessage(
                    event,
                    lang.msg(
                        "koth.reminder",
                        "koth_name" to event.arena.id,
                        "capper" to (capperName(event) ?: "None"),
                        "time_left" to formatTime(event.endsAt.epochSecond - now.epochSecond),
                    ),
                )
            } else reminderCounters[event.arena.id] = counter - 1
        }

        val teamsInZone = resolveTeams(playersInZone, arena)
        if (arena.family.equals("conquest", true)) tickConquest(event, arena, teamsInZone, playersInZone)
        else tickCaptureFamily(event, arena, teamsInZone)

        val contested = teamsInZone.size > 1
        val timeLeft = formatTime(event.endsAt.epochSecond - now.epochSecond)
        displayService.showKoth(
            arena.id,
            capperName(event),
            timeLeft,
            contested,
            eventRecipients(event),
            !event.isPrivateTest,
        )

        if (!event.isPrivateTest && cfg.discord.enabled && cfg.discord.liveUpdateSeconds > 0) {
            val last = discordLastUpdate
            if (last == null || now.epochSecond - last >= cfg.discord.liveUpdateSeconds) {
                discordLastUpdate = now.epochSecond
                discordWebhook.sendLiveUpdate(event.arena.id, event.currentController, contested, timeLeft)
            }
        }
    }

    private fun tickCaptureFamily(event: KothEvent, arena: KothArena, teamsInZone: List<TeamId>) {
        val controller = event.currentController
        if (controller == null) {
            if (teamsInZone.size == 1) {
                val team = teamsInZone.first()
                event.currentController = team
                event.leaveAnnouncedFor = null
                sendEventMessage(
                    event,
                    lang.msg(
                        "koth.enter",
                        "entered" to teamName(team),
                        "koth_name" to arena.id,
                        "captime" to formatTime(arena.captureSeconds.toLong()),
                    ),
                )
            }
            return
        }
        if (controller !in teamsInZone || (arena.contestWhenMultipleCappers && teamsInZone.size > 1)) {
            performLeave(event)
        } else {
            tickCaptureProgress(event, arena)
        }
    }

    private fun tickConquest(event: KothEvent, arena: KothArena, teamsInZone: List<TeamId>, players: List<Player>) {
        if (teamsInZone.size != 1) {
            event.currentController = null
            return
        }
        val team = teamsInZone.first()
        event.currentController = team
        val capperCount = players.count { player -> teamFor(player, arena) == team }
        val multiplier = arena.captureSpeedBonuses[capperCount] ?: 1.0
        event.addScore(team, multiplier)
    }

    private fun tickCaptureProgress(event: KothEvent, arena: KothArena) {
        val controller = event.currentController ?: return
        val next = min(arena.captureSeconds.toDouble(), (event.scores[controller] ?: 0.0) + 1.0)
        event.scores[controller] = next
        val secondsLeft = arena.captureSeconds - next.toInt()
        if (secondsLeft > 0 && secondsLeft % 30 == 0) {
            sendEventMessage(
                event,
                lang.msg(
                    "koth.capping",
                    "capping" to teamName(controller),
                    "koth_name" to arena.id,
                    "time_left" to formatTime(secondsLeft.toLong()),
                ),
            )
        }
        if (next >= arena.captureSeconds) finishEvent(event, controller)
    }

    private fun playersInCuboid(event: KothEvent): List<Player> =
        Bukkit.getOnlinePlayers().filter { player ->
            player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR &&
                event.isParticipant(player.uniqueId) && event.arena.zone.contains(player.location)
        }

    private fun playersNearMovingPoint(event: KothEvent): List<Player> {
        val arena = event.arena
        val point = calculateMovingPoint(event)
        event.movingPoint = point
        val world = Bukkit.getWorld(arena.zone.worldName) ?: return emptyList()
        val (x, _, z) = point
        return Bukkit.getOnlinePlayers().filter { player ->
            player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR &&
                event.isParticipant(player.uniqueId) && player.world.name == arena.zone.worldName &&
                player.location.distanceSquared(org.bukkit.Location(world, x, player.location.y, z)) <= arena.zone.radiusSq
        }
    }

    private fun calculateMovingPoint(event: KothEvent): Triple<Double, Double, Double> {
        val world = Bukkit.getWorld(event.arena.zone.worldName) ?: return Triple(0.0, 80.0, 0.0)
        val center = event.arena.zone.center(world)
        val elapsed = (System.currentTimeMillis() - event.startsAt.toEpochMilli()).coerceAtLeast(0) / 1000.0
        val (x, z) = movingPointAt(
            elapsed,
            event.arena.movingSquareSize,
            event.arena.movingSpeedBlocksPerSecond,
            center.x,
            center.z,
        )
        return Triple(x, center.y, z)
    }

    private fun resolveWinner(event: KothEvent): TeamId? {
        val (winner, score) = event.scores.maxByOrNull { it.value } ?: return null
        if (score <= 0.0 || event.scores.count { it.value == score } > 1) return null
        return winner
    }

    private fun performLeave(event: KothEvent) {
        val controller = event.currentController ?: return
        if (event.leaveAnnouncedFor != controller) {
            event.leaveAnnouncedFor = controller
            sendEventMessage(
                event,
                lang.msg(
                    "koth.leave",
                    "left" to teamName(controller),
                    "koth_name" to event.arena.id,
                    "time_left" to formatTime(event.arena.captureSeconds.toLong()),
                ),
            )
        }
        when (event.arena.leaveBehavior) {
            CaptureLeaveBehavior.RESET -> {
                event.scores.clear()
                event.currentController = null
            }
            CaptureLeaveBehavior.DECAY -> {
                val next = ((event.scores[controller] ?: 0.0) - event.arena.decayPerSecond).coerceAtLeast(0.0)
                if (next <= 0.0) {
                    event.scores.remove(controller)
                    event.currentController = null
                } else event.scores[controller] = next
            }
            CaptureLeaveBehavior.PAUSE -> event.currentController = null
        }
    }

    private fun finishEvent(event: KothEvent, winner: TeamId?) {
        event.state = EventState.COMPLETED
        if (winner != null) {
            sendEventMessage(
                event,
                lang.msg("koth.capture", "captured" to teamName(winner), "koth_name" to event.arena.id),
            )
            if (!event.isPrivateTest) {
                stats.incrementWin(winner.storageKey(), event.arena.id)
                stats.save()
                executeRewards(event, winner)
            }
        }
        val wasContested = event.scores.size > 1
        event.clearScores()
        event.currentController = null
        activeEvent = null
        reminderCounters.remove(event.arena.id)
        discordLastUpdate = null
        displayService.clear()
        zoneBorderService.hide()
        if (winner != null && !event.isPrivateTest) {
            discordWebhook.sendCapture(event.arena.id, winner, wasContested)
            fireworkService.celebrate(event.arena.zone)
        }
        processQueue()
    }

    private fun executeRewards(event: KothEvent, winner: TeamId) {
        val arena = event.arena
        val name = sanitizeName(teamName(winner))
        val guildId = winner.id.takeIf { winner.mode == TeamMode.GUILD }
        cfgLoader().rewards[arena.family.lowercase()]?.let { reward ->
            if (winner.mode == TeamMode.GUILD && reward.guildVaultMoney > 0.0) {
                guilds.depositToVault(winner.id, reward.guildVaultMoney, "KOTH win reward")
            } else if (winner.mode == TeamMode.SOLO && reward.soloVaultMoney > 0.0) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give $name ${reward.soloVaultMoney}")
            }
        }
        arena.rewards.forEach { executeRewardCommand(it, event, name, guildId) }
        arena.chancedRewards.forEach { (command, chance) ->
            if (ThreadLocalRandom.current().nextDouble() * 100.0 < chance) executeRewardCommand(command, event, name, guildId)
        }
    }

    private fun executeRewardCommand(command: String, event: KothEvent, name: String, guildId: UUID?) {
        if (executeBankReward(command, guildId)) return
        val resolved = command.replace("{PLAYER}", name).replace("{FACTION}", name).replace("{KOTH}", event.arena.id)
        if (resolved.contains("{ALL_ONLINE}") && guildId != null) {
            guilds.onlineMembers(guildId).forEach { member ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved.replace("{ALL_ONLINE}", member.name))
            }
        } else if (resolved.contains("{ALL_ONLINE}")) {
            Bukkit.getOnlinePlayers().forEach { player ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved.replace("{ALL_ONLINE}", player.name))
            }
        } else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)
    }

    private fun executeBankReward(command: String, guildId: UUID?): Boolean {
        if (!command.startsWith("bank", ignoreCase = true)) return false
        if (guildId == null) return true
        val amount = command.split("\\s+".toRegex()).getOrNull(1)?.toDoubleOrNull() ?: return true
        guilds.depositToVault(guildId, amount, "KOTH guild reward")
        return true
    }

    @Synchronized
    fun startEvent(
        arena: KothArena,
        durationOverride: Int? = null,
        kind: EventKind = EventKind.PLAYER_COMMAND,
        delaySeconds: Int = 0,
    ): Boolean {
        if (activeEvent != null) return false
        val cfg = cfgLoader()
        if (!cfg.locks.state.allows(kind)) return false
        val now = Instant.now()
        val start = now.plusSeconds(delaySeconds.coerceAtLeast(0).toLong())
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = arena,
            startsAt = start,
            endsAt = start.plusSeconds((durationOverride ?: arena.durationSeconds).toLong()),
            state = if (delaySeconds > 0) EventState.STARTING else EventState.ACTIVE,
        )
        activeEvent = event
        if (event.state == EventState.ACTIVE) activateEvent(event, cfg)
        return true
    }

    @Synchronized
    fun queueStart(arena: KothArena, kind: EventKind = EventKind.SCHEDULED): Boolean {
        if (activeEvent == null) return startEvent(arena, kind = kind)
        eventQueue.add(QueuedEvent(arena, kind, Instant.now()))
        return true
    }

    @Synchronized
    fun processQueue() {
        val next = eventQueue.removeFirstOrNull() ?: return
        startEvent(next.arena, kind = next.startSource)
    }

    fun nextQueued(): QueuedEvent? = synchronized(this) { eventQueue.firstOrNull() }
    fun queuedEvents(): List<QueuedEvent> = synchronized(this) { eventQueue.toList() }
    fun clearQueue() = synchronized(this) { eventQueue.clear() }

    @Synchronized
    fun startPrivateTest(arena: KothArena, ownerId: UUID, cfg: EnthusiaKothConfig): Boolean {
        if (activeEvent != null || !cfg.locks.state.allows(EventKind.PRIVATE_TEST)) return false
        val testing = cfg.privateTesting
        val lobbySeconds = testing.lobbySeconds.coerceAtLeast(0)
        val duration = testing.quickMatchDurationSeconds.takeIf { it > 0 } ?: arena.durationSeconds
        val capture = testing.quickCaptureSeconds.takeIf { it > 0 } ?: arena.captureSeconds
        val now = Instant.now()
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = arena.copy(durationSeconds = duration, captureSeconds = capture),
            startsAt = now.plusSeconds(lobbySeconds.toLong()),
            endsAt = now.plusSeconds((lobbySeconds + duration).toLong()),
            state = if (lobbySeconds > 0) EventState.STARTING else EventState.ACTIVE,
            owner = ownerId,
            isPrivateTest = true,
            lobbySeconds = lobbySeconds,
        )
        event.join(ownerId)
        activeEvent = event
        Bukkit.getPlayer(ownerId)?.let { owner ->
            if (lobbySeconds > 0) owner.sendMessage(lang.msg("private.lobby_open", "seconds" to lobbySeconds.toString()))
            else {
                owner.sendMessage(lang.msg("private.started", "duration" to duration.toString(), "capture" to capture.toString()))
                if (testing.showObjectiveParticles) owner.sendMessage(lang.msg("private.objective_particles"))
            }
        }
        return true
    }

    @Synchronized
    fun forceEnd(): Boolean {
        val event = activeEvent ?: return false
        sendEventMessage(event, lang.msg("koth.ended", "koth_name" to event.arena.id))
        event.state = EventState.CANCELLED
        event.clearScores()
        event.currentController = null
        activeEvent = null
        reminderCounters.remove(event.arena.id)
        discordLastUpdate = null
        displayService.clear()
        zoneBorderService.hide()
        return true
    }

    private fun resolveTeams(players: List<Player>, arena: KothArena): List<TeamId> =
        players.map { teamFor(it, arena) }.distinct()

    private fun teamFor(player: Player, arena: KothArena): TeamId {
        if (arena.ignoreFactions) return TeamId(TeamMode.SOLO, player.uniqueId)
        return guilds.playerGuildId(player)?.let { TeamId(TeamMode.GUILD, it) }
            ?: TeamId(TeamMode.SOLO, player.uniqueId)
    }

    private fun teamName(team: TeamId): String = if (team.mode == TeamMode.GUILD) {
        guilds.guildName(team.id) ?: team.id.toString().take(8)
    } else Bukkit.getOfflinePlayer(team.id).name ?: team.id.toString().take(8)

    private fun sanitizeName(raw: String): String = raw
        .replace(Regex("§[0-9a-fk-orA-FK-OR]"), "")
        .replace(Regex("<[^>]*>"), "")
        .filter { it.isLetterOrDigit() || it == '_' }

    fun capperName(event: KothEvent): String? = event.currentController?.let(::teamName)

    private fun locString(zone: CaptureZone): String {
        val world = Bukkit.getWorld(zone.worldName) ?: return "?,?,?"
        val center = zone.center(world)
        return "${center.blockX}, ${center.blockY}, ${center.blockZ}"
    }

    private fun progressBar(event: KothEvent, cfg: ProgressBarConfig): net.kyori.adventure.text.Component {
        val maximum = event.arena.captureSeconds.coerceAtLeast(1).toDouble()
        val progress = ((event.scores.values.maxOrNull() ?: 0.0) / maximum).coerceIn(0.0, 1.0)
        val filled = (progress * cfg.length).toInt()
        val bar = cfg.character.repeat(filled) + lang.raw("progress_bar.empty_color") +
            cfg.character.repeat((cfg.length - filled).coerceAtLeast(0))
        return lang.msg("progress_bar.format", "progress_bar" to bar)
    }

    private fun eventRecipients(event: KothEvent): List<Player> = if (event.isPrivateTest) {
        event.participants.mapNotNull(Bukkit::getPlayer)
    } else Bukkit.getOnlinePlayers().toList()

    private fun sendEventMessage(event: KothEvent, message: net.kyori.adventure.text.Component) {
        if (event.isPrivateTest) eventRecipients(event).forEach { it.sendMessage(message) }
        else Bukkit.broadcast(message)
    }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val remainder = safe % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }

    fun shutdown() {
        forceEnd()
    }
}
