package net.badgersmc.ek.infrastructure.lumaguilds

import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import net.lumalyte.lg.api.GuildLookup
import net.lumalyte.lg.api.GuildSummary
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*

/**
 * Direct API adapter for LumaGuilds.
 * GuildLookup is registered in Bukkit's ServicesManager by LumaGuilds on enable.
 * No reflection — purely Bukkit ServiceManager + compile-time API.
 *
 * All methods return Kotlin nullable types (not Java Optional) for idiomatic use.
 */
class LumaGuildsAdapter {

    private var cached: GuildLookup? = null

    /**
     * Resolves GuildLookup from the ServicesManager, retrying on each call if
     * LumaGuilds hadn't registered it yet (avoid permanently caching a null).
     */
    private val lookup: GuildLookup?
        get() {
            val existing = cached
            if (existing != null) return existing
            return Bukkit.getServicesManager().load(GuildLookup::class.java).also { loaded ->
                if (loaded != null) cached = loaded
            }
        }

    fun isAvailable(): Boolean = lookup != null

    fun playerGuildId(player: Player): UUID? {
        val lk = lookup ?: return null
        val ids = lk.getPlayerGuildIds(player.uniqueId)
        return ids.firstOrNull()
    }

    /** Returns the guild TeamId, or null if the player isn't in a guild. */
    fun playerGuild(player: Player): TeamId? {
        val id = playerGuildId(player) ?: return null
        return TeamId(TeamMode.GUILD, id)
    }

    /** Returns the guild's display name (tag if set, otherwise name), or null. */
    fun guildName(guildId: UUID): String? {
        val lk = lookup ?: return null
        val guild = lk.getGuild(guildId) ?: return null
        val tag = guild.tag
        return if (!tag.isNullOrBlank()) tag
            else guild.name ?: guildId.toString().take(8)
    }

    /** Returns just the tag, or the name if no tag, or null. */
    fun guildTag(guildId: UUID): String? {
        val lk = lookup ?: return null
        val guild = lk.getGuild(guildId) ?: return null
        val tag = guild.tag
        return if (tag.isNullOrBlank()) guild.name else tag
    }

    fun isInGuild(player: Player): Boolean = playerGuildId(player) != null

    fun depositToVault(guildId: UUID, amount: Double, reason: String): Boolean {
        val lk = lookup ?: return false
        val units = amount.toLong()
        if (units <= 0) return false
        return lk.bankDeposit(guildId, guildId, units, reason)
    }

    fun withdrawFromVault(guildId: UUID, amount: Double, reason: String): Boolean {
        val lk = lookup ?: return false
        val units = amount.toLong()
        if (units <= 0) return false
        return lk.bankWithdraw(guildId, guildId, units, reason)
    }

    fun getBalance(guildId: UUID): Long {
        val lk = lookup ?: return 0L
        return lk.getBankBalance(guildId)
    }

    fun onlineMembers(guildId: UUID): List<Player> {
        val lk = lookup ?: return emptyList()
        val memberIds = lk.getGuildMemberIds(guildId)
        return memberIds.mapNotNull { Bukkit.getPlayer(it) }
    }

    fun getGuildSummary(guildId: UUID): GuildSummary? {
        val lk = lookup ?: return null
        return lk.getGuild(guildId)
    }
}
