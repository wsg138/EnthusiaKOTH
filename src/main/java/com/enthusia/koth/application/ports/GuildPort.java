package com.enthusia.koth.application.ports;

import com.enthusia.koth.domain.team.TeamSnapshot;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public interface GuildPort {
    boolean isAvailable();
    Optional<TeamSnapshot> playerGuild(Player player);
    Optional<TeamSnapshot> guild(UUID guildId);
    TransactionResult depositGuildReward(UUID guildId, double amount, String reason);
}
