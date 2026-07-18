package com.enthusia.koth.application.reward;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.ports.EconomyPort;
import com.enthusia.koth.application.ports.GuildPort;
import com.enthusia.koth.application.ports.StatsRepository;
import com.enthusia.koth.application.ports.TransactionResult;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bukkit.Bukkit;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class RewardService {
    private final ConfigurationService config;
    private final EconomyPort economy;
    private final GuildPort guilds;
    private final StatsRepository stats;
    private final Logger logger;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Application ports and logger are shared by dependency injection.")
    public RewardService(ConfigurationService config, EconomyPort economy, GuildPort guilds, StatsRepository stats, Logger logger) {
        this.config = config;
        this.economy = economy;
        this.guilds = guilds;
        this.stats = stats;
        this.logger = logger;
    }

    public Optional<String> rewardWinner(ActiveEvent event, Optional<String> winnerKey) {
        if (winnerKey.isEmpty()) {
            return Optional.empty();
        }
        TeamId teamId = parse(winnerKey.get());
        if (event.isPrivateTest()) {
            return displayName(teamId);
        }
        if (teamId.mode() == TeamMode.SOLO) {
            double amount = config.settings().soloRewards().getOrDefault(event.request().family(), 0.0);
            UUID playerId = teamId.id();
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(playerId).getName()).orElse(playerId.toString());
            if (amount > 0) {
                TransactionResult result = economy.deposit(Bukkit.getOfflinePlayer(playerId), amount, "KOTH " + event.request().family().key() + " reward");
                if (!result.success()) {
                    logger.warning("Failed to pay solo KOTH reward to " + playerId + ": " + result.message());
                }
            }
            stats.incrementPlayerWin(playerId, name, event.request().family());
            return Optional.of(name);
        }

        double amount = config.settings().guildRewards().getOrDefault(event.request().family(), 0.0);
        String display = guilds.guild(teamId.id()).map(snapshot -> snapshot.displayName()).orElse(teamId.id().toString());
        if (amount > 0) {
            TransactionResult result = guilds.depositGuildReward(teamId.id(), amount, "KOTH " + event.request().family().key() + " reward");
            if (!result.success()) {
                logger.warning("Failed to pay guild KOTH reward to " + teamId.id() + ": " + result.message());
            }
        }
        stats.incrementGuildWin(teamId.id(), display, event.request().family());
        return Optional.of(display);
    }

    private TeamId parse(String key) {
        String[] parts = key.split(":", 2);
        return new TeamId(TeamMode.valueOf(parts[0].toUpperCase(Locale.ROOT)), UUID.fromString(parts[1]));
    }

    private Optional<String> displayName(TeamId teamId) {
        if (teamId.mode() == TeamMode.SOLO) {
            return Optional.of(Optional.ofNullable(Bukkit.getOfflinePlayer(teamId.id()).getName()).orElse(teamId.id().toString()));
        }
        return Optional.of(guilds.guild(teamId.id()).map(snapshot -> snapshot.displayName()).orElse(teamId.id().toString()));
    }
}
