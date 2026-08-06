package net.badgersmc.ek.infrastructure.papi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PlaceholderResolverTest {
    private val now = Instant.parse("2026-08-06T12:00:00Z")
    private val player = UUID.randomUUID()
    private val secondPlayer = UUID.randomUUID()
    private val guild = UUID.randomUUID()

    @Test
    fun `documented current and next placeholders return values`() {
        val resolver = resolver()
        assertEquals("True", resolver.resolve(player, "is_active"))
        assertEquals("capture", resolver.resolve(player, "currentkoth"))
        assertEquals("capture", resolver.resolve(player, "CURRENT_KOTH"))
        assertEquals("Guild One", resolver.resolve(player, "current_capper"))
        assertEquals("2m 5s", resolver.resolve(player, "current_timeleft"))
        assertEquals("moving", resolver.resolve(player, "nextkoth"))
        assertEquals("moving", resolver.resolve(player, "NEXT_KOTH"))
        assertEquals("15m 0s", resolver.resolve(player, "nextkothtime"))
        assertEquals("15m 0s", resolver.resolve(player, "NEXT_KOTH_TIME"))
    }

    @Test
    fun `player win parsing is case-insensitive and uses canonical arena id`() {
        val resolver = resolver()
        assertEquals("7", resolver.resolve(player, "wins"))
        assertEquals("3", resolver.resolve(player, "wins_capture"))
        assertEquals("3", resolver.resolve(player, "WINS_CAPTURE"))
        assertEquals("3", resolver.resolve(player, "WiNs_CaPtUrE"))
        assertEquals("0", resolver.resolve(player, "wins_missing"))
    }

    @Test
    fun `arena placeholders return active state and player arena stats`() {
        val resolver = resolver()
        assertEquals("2m 5s", resolver.resolve(player, "capture_timeleft"))
        assertEquals("Guild One", resolver.resolve(player, "CAPTURE_CAPPER"))
        assertEquals("3", resolver.resolve(player, "capture_wins"))
        assertEquals("Not Active", resolver.resolve(player, "moving_timeleft"))
        assertEquals("None", resolver.resolve(player, "moving_capper"))
    }

    @Test
    fun `player and guild leaderboard placeholders use separate prefixes`() {
        val resolver = resolver()
        assertEquals("Lincoln", resolver.resolve(player, "top_player_1_name"))
        assertEquals("7", resolver.resolve(player, "top_player_1_wins"))
        assertEquals("Second", resolver.resolve(player, "TOP_PLAYER_2_NAME"))
        assertEquals("Guild One", resolver.resolve(player, "top_guild_1_name"))
        assertEquals("11", resolver.resolve(player, "top_guild_1_wins"))
    }

    @Test
    fun `disabled schedule missing player and unknown placeholders are safe`() {
        val resolver = resolver(next = null)
        assertEquals("None", resolver.resolve(player, "nextkoth"))
        assertEquals("0s", resolver.resolve(player, "nextkothtime"))
        assertEquals("0", resolver.resolve(null, "wins"))
        assertEquals("0", resolver.resolve(null, "capture_wins"))
        assertEquals("", resolver.resolve(player, "unknown_placeholder"))
        assertEquals("", resolver.resolve(player, "top_player_99_name"))
    }

    @Test
    fun `private event state can be hidden from nonparticipants`() {
        val participant = player
        val resolver = resolver(active = { id ->
            if (id == participant) ActivePlaceholderState("capture", "Guild One", now.plusSeconds(125)) else null
        })
        assertEquals("True", resolver.resolve(participant, "is_active"))
        assertEquals("False", resolver.resolve(secondPlayer, "is_active"))
        assertEquals("None", resolver.resolve(secondPlayer, "currentkoth"))
    }

    private fun resolver(
        next: Pair<String, String>? = "moving" to "15m 0s",
        active: (UUID?) -> ActivePlaceholderState? = {
            ActivePlaceholderState("capture", "Guild One", now.plusSeconds(125))
        },
    ): PlaceholderResolver {
        val totals = mapOf(
            "solo:$player" to 7,
            "solo:$secondPlayer" to 4,
            "guild:$guild" to 11,
        )
        return PlaceholderResolver(
            clock = Clock.fixed(now, ZoneOffset.UTC),
            activeState = active,
            nextEvent = { next },
            arenaIds = { listOf("capture", "moving") },
            totalWins = { totals[it] ?: 0 },
            arenaWins = { key, arena -> if (key == "solo:$player" && arena == "capture") 3 else 0 },
            allWins = { totals },
            playerName = {
                when (it) {
                    player -> "Lincoln"
                    secondPlayer -> "Second"
                    else -> null
                }
            },
            guildName = { if (it == guild) "Guild One" else null },
        )
    }
}
