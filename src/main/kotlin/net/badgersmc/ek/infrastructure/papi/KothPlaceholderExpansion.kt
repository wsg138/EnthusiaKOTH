package net.badgersmc.ek.infrastructure.papi

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Clock

class KothPlaceholderExpansion(
    private val kothService: KothService,
    private val scheduleService: ScheduleService,
    private val stats: SqlStatsRepository,
    private val guilds: LumaGuildsAdapter,
    private val arenas: () -> Map<String, KothArena>,
    clock: Clock,
) : PlaceholderExpansion() {
    private val resolver = PlaceholderResolver(
        clock = clock,
        activeState = state@ { playerId ->
            val event = kothService.activeEvent ?: return@state null
            if (event.isPrivateTest && (playerId == null || !event.isParticipant(playerId))) return@state null
            ActivePlaceholderState(event.arena.id, kothService.capperName(event), event.endsAt)
        },
        nextEvent = scheduleService::nextEventInfo,
        arenaIds = { arenas().keys },
        totalWins = stats::totalWins,
        arenaWins = stats::kothWins,
        allWins = stats::allWins,
        playerName = { Bukkit.getOfflinePlayer(it).name },
        guildName = guilds::guildName,
    )

    override fun getIdentifier(): String = "enthusiakoth"
    override fun getAuthor(): String = "BadgersMC"
    override fun getVersion(): String = "0.2.0"
    override fun persist(): Boolean = true
    override fun canRegister(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String =
        resolver.resolve(player?.uniqueId, params)
}
