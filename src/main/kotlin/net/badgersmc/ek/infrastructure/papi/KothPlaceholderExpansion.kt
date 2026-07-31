package net.badgersmc.ek.infrastructure.papi

import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

/**
 * PlaceholderAPI expansion for EnthusiaKOTH.
 * Inspired by FactionsKore's extensive placeholder support.
 *
 * Placeholders:
 * - %enthusiakoth_<arena>_timeleft% — time remaining on a KOTH
 * - %enthusiakoth_<arena>_capper% — who's currently capping
 * - %enthusiakoth_<arena>_wins% — player's wins on that KOTH
 * - %enthusiakoth_wins% — player's total KOTH wins
 * - %enthusiakoth_currentkoth% — name of active KOTH (or "None")
 * - %enthusiakoth_current_capper% — current capper name
 * - %enthusiakoth_current_timeleft% — time left on active KOTH
 * - %enthusiakoth_is_active% — "True" or "False"
 * - %enthusiakoth_nextkoth% — name of next scheduled KOTH
 * - %enthusiakoth_nextkothtime% — time until next KOTH
 */
class KothPlaceholderExpansion(
    private val kothService: KothService,
    private val stats: SqlStatsRepository,
    private val guilds: LumaGuildsAdapter,
    private val arenas: () -> Map<String, KothArena>,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "enthusiakoth"

    override fun getAuthor(): String = "BadgersMC"

    override fun getVersion(): String = "0.2.0"

    override fun onPlaceholderRequest(player: Player?, params: String): String {
        val event = kothService.activeEvent

        // Simple placeholders
        when (params.lowercase()) {
            "is_active" -> return if (event != null) "True" else "False"
            "currentkoth", "current_koth" -> return event?.arena?.id ?: "None"
            "current_capper", "currentcapper" -> {
                if (event == null) return "None"
                return kothService.capperName(event) ?: "None"
            }
            "current_timeleft", "currenttimeleft" -> {
                if (event == null) return "Not Active"
                val secs = event.endsAt.epochSecond - System.currentTimeMillis() / 1000
                return formatTime(secs.coerceAtLeast(0))
            }
            "nextkoth", "next_koth" -> return "Not implemented"  // TODO
            "nextkothtime", "next_koth_time" -> return "0s"
        }

        // Player-scoped placeholders
        if (player != null) {
            if (params.equals("wins", ignoreCase = true)) {
                return stats.totalWins("solo:${player.uniqueId}").toString()
            }
            if (params.startsWith("wins_", ignoreCase = true)) {
                val kothName = params.removePrefix("wins_").removePrefix("wins_")
                return stats.kothWins("solo:${player.uniqueId}", kothName).toString()
            }
        }

        // Arena-scoped placeholders: <arena>_timeleft, <arena>_capper, <arena>_wins
        val arena = arenas().entries.firstOrNull { (id, _) ->
            params.startsWith(id, ignoreCase = true)
        } ?: return ""

        val suffix = params.removePrefix(arena.key).removePrefix("_")
        return when (suffix.lowercase()) {
            "timeleft" -> {
                if (event?.arena?.id != arena.key) return "Not Active"
                val secs = event.endsAt.epochSecond - System.currentTimeMillis() / 1000
                formatTime(secs.coerceAtLeast(0))
            }
            "capper" -> {
                if (event?.arena?.id != arena.key) return "None"
                kothService.capperName(event) ?: "None"
            }
            "wins" -> {
                if (player == null) return "0"
                stats.kothWins("solo:${player.uniqueId}", arena.key).toString()
            }
            else -> ""
        }
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
