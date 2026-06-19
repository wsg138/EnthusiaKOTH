package com.enthusia.koth.infrastructure.placeholder;

import com.enthusia.koth.application.ports.LeaderboardEntry;
import com.enthusia.koth.application.ports.StatsRepository;
import com.enthusia.koth.domain.KothFamily;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public final class EnthusiaKothExpansion extends PlaceholderExpansion {
    private final StatsRepository stats;

    public EnthusiaKothExpansion(StatsRepository stats) {
        this.stats = stats;
    }

    @Override public @NotNull String getIdentifier() { return "enthusiakoth"; }
    @Override public @NotNull String getAuthor() { return "Enthusia"; }
    @Override public @NotNull String getVersion() { return "0.1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String[] parts = params.split("_");
        if (parts.length != 3) {
            return "";
        }
        Optional<KothFamily> family = parts[0].equalsIgnoreCase("all") ? Optional.empty() : KothFamily.fromKey(parts[0]);
        if (family.isEmpty() && !parts[0].equalsIgnoreCase("all")) {
            return "";
        }
        int rank;
        try {
            rank = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            return "";
        }
        if (rank < 1 || rank > 11) {
            return "";
        }
        List<LeaderboardEntry> entries = parts[1].equalsIgnoreCase("guild")
                ? stats.topGuilds(family, 11)
                : stats.topPlayers(family, 11);
        if (entries.size() < rank) {
            return "";
        }
        LeaderboardEntry entry = entries.get(rank - 1);
        if (parts[1].equalsIgnoreCase("uuid")) {
            return entry.id().toString();
        }
        if (parts[1].equalsIgnoreCase("player") || parts[1].equalsIgnoreCase("guild")) {
            return entry.displayName();
        }
        return "";
    }
}
