package com.enthusia.koth.application.ports;

import com.enthusia.koth.domain.KothFamily;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatsRepository {
    void incrementPlayerWin(UUID playerId, String lastKnownName, KothFamily family);
    void incrementGuildWin(UUID guildId, String displayName, KothFamily family);
    List<LeaderboardEntry> topPlayers(Optional<KothFamily> family, int limit);
    List<LeaderboardEntry> topGuilds(Optional<KothFamily> family, int limit);
    void save();
    void reload();
}
