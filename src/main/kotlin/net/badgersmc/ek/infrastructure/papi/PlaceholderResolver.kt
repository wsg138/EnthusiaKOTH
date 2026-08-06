package net.badgersmc.ek.infrastructure.papi

import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ActivePlaceholderState(
    val arenaId: String,
    val capper: String?,
    val endsAt: Instant,
)

class PlaceholderResolver(
    private val clock: Clock,
    private val activeState: (UUID?) -> ActivePlaceholderState?,
    private val nextEvent: () -> Pair<String, String>?,
    private val arenaIds: () -> Collection<String>,
    private val totalWins: (String) -> Int,
    private val arenaWins: (String, String) -> Int,
    private val allWins: () -> Map<String, Int>,
    private val playerName: (UUID) -> String?,
    private val guildName: (UUID) -> String?,
) {
    fun resolve(playerId: UUID?, rawParams: String): String {
        val params = rawParams.trim()
        val normalized = params.lowercase()
        val active = activeState(playerId)

        when (normalized) {
            "is_active" -> return if (active != null) "True" else "False"
            "currentkoth", "current_koth" -> return active?.arenaId ?: "None"
            "current_capper", "currentcapper" -> return active?.capper ?: "None"
            "current_timeleft", "currenttimeleft" -> return active?.let { formatTime(it.endsAt.epochSecond - clock.instant().epochSecond) } ?: "Not Active"
            "nextkoth", "next_koth" -> return nextEvent()?.first ?: "None"
            "nextkothtime", "next_koth_time" -> return nextEvent()?.second ?: "0s"
            "wins" -> return playerId?.let { totalWins("solo:$it").toString() } ?: "0"
        }

        if (normalized.startsWith("wins_")) {
            val player = playerId ?: return "0"
            val requested = params.substring(5)
            val arena = arenaIds().firstOrNull { it.equals(requested, ignoreCase = true) } ?: return "0"
            return arenaWins("solo:$player", arena).toString()
        }

        parseLeaderboard(normalized)?.let { request ->
            val entries = allWins().entries
                .asSequence()
                .filter { it.key.startsWith("${request.prefix}:") }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .toList()
            val entry = entries.getOrNull(request.rank - 1) ?: return ""
            if (request.field == "wins") return entry.value.toString()
            val id = entry.key.substringAfter(':').let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return entry.key.substringAfter(':').take(8)
            return when (request.prefix) {
                "solo" -> playerName(id) ?: id.toString().take(8)
                "guild" -> guildName(id) ?: id.toString().take(8)
                else -> ""
            }
        }

        val arena = arenaIds().firstOrNull { id ->
            params.length > id.length && params.startsWith(id, ignoreCase = true) && params[id.length] == '_'
        } ?: return ""
        val suffix = params.substring(arena.length + 1).lowercase()
        return when (suffix) {
            "timeleft" -> if (active?.arenaId.equals(arena, ignoreCase = true)) formatTime(active!!.endsAt.epochSecond - clock.instant().epochSecond) else "Not Active"
            "capper" -> if (active?.arenaId.equals(arena, ignoreCase = true)) active?.capper ?: "None" else "None"
            "wins" -> playerId?.let { arenaWins("solo:$it", arena).toString() } ?: "0"
            else -> ""
        }
    }

    private fun parseLeaderboard(value: String): LeaderboardRequest? {
        val match = Regex("top_(player|guild)_(\\d+)_(name|wins)").matchEntire(value) ?: return null
        val rank = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return LeaderboardRequest(
            prefix = if (match.groupValues[1] == "player") "solo" else "guild",
            rank = rank,
            field = match.groupValues[3],
        )
    }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val remainder = safe % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }

    private data class LeaderboardRequest(val prefix: String, val rank: Int, val field: String)
}
