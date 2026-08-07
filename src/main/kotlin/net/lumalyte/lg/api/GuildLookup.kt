// Compile-time mirror of LumaGuilds' deliberately public ServicesManager API.
// This package is excluded from the shadow JAR; runtime classes come from the
// required LumaGuilds dependency through Paper join-classpath.
package net.lumalyte.lg.api

import java.util.UUID

interface GuildLookup {
    fun getPlayerGuildIds(playerId: UUID): Set<UUID>
    fun getGuild(guildId: UUID): GuildSummary?
    fun getAllGuilds(): List<GuildSummary>
    fun isMember(playerId: UUID, guildId: UUID): Boolean
    fun hasShopPermission(playerId: UUID, guildId: UUID, permission: String): Boolean
    fun hasRankAtLeast(playerId: UUID, guildId: UUID, rankName: String): Boolean
    fun getGuildMemberIds(guildId: UUID): Set<UUID>
    fun getBankBalance(guildId: UUID): Long
    fun bankWithdraw(guildId: UUID, actorId: UUID, amount: Long, reason: String): Boolean
    fun bankDeposit(guildId: UUID, actorId: UUID, amount: Long, reason: String): Boolean
}

data class GuildSummary(
    val id: UUID,
    val name: String,
    val tag: String?,
    val emoji: String?,
)
