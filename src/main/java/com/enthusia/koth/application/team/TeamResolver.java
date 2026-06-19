package com.enthusia.koth.application.team;

import com.enthusia.koth.application.ports.GuildPort;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.team.TeamId;
import org.bukkit.entity.Player;

import java.util.Optional;

public final class TeamResolver {
    private final GuildPort guildPort;

    public TeamResolver(GuildPort guildPort) {
        this.guildPort = guildPort;
    }

    public Optional<TeamId> resolve(Player player, TeamMode mode) {
        if (mode == TeamMode.SOLO) {
            return Optional.of(new TeamId(TeamMode.SOLO, player.getUniqueId()));
        }
        return guildPort.playerGuild(player).map(snapshot -> snapshot.id());
    }
}
