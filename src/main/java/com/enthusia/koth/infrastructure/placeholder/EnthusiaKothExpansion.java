package com.enthusia.koth.infrastructure.placeholder;

import com.enthusia.koth.application.ports.LeaderboardEntry;
import com.enthusia.koth.application.ports.StatsRepository;
import com.enthusia.koth.domain.KothFamily;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

@SuppressFBWarnings(value = "HE_INHERITS_EQUALS_USE_HASHCODE", justification = "PlaceholderExpansion defines final identity equality.")
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
        Optional<Optional<KothFamily>> family = parseFamily(parts[0]);
        Optional<Integer> rank = parseRank(parts[2]);
        if (family.isEmpty() || rank.isEmpty()) {
            return "";
        }
        return findEntry(parts[1], family.get(), rank.get())
                .map(entry -> formatEntry(parts[1], entry))
                .orElse("");
    }

    private Optional<Optional<KothFamily>> parseFamily(String raw) {
        if (raw.equalsIgnoreCase("all")) {
            return Optional.of(Optional.empty());
        }
        return KothFamily.fromKey(raw).map(Optional::of);
    }

    private Optional<Integer> parseRank(String raw) {
        try {
            int rank = Integer.parseInt(raw);
            return rank < 1 || rank > 11 ? Optional.empty() : Optional.of(rank);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private Optional<LeaderboardEntry> findEntry(String type, Optional<KothFamily> family, int rank) {
        List<LeaderboardEntry> entries = leaderboard(type, family);
        return entries.size() < rank ? Optional.empty() : Optional.of(entries.get(rank - 1));
    }

    private List<LeaderboardEntry> leaderboard(String type, Optional<KothFamily> family) {
        if (type.equalsIgnoreCase("guild")) {
            return stats.topGuilds(family, 11);
        }
        return stats.topPlayers(family, 11);
    }

    private String formatEntry(String type, LeaderboardEntry entry) {
        if (type.equalsIgnoreCase("uuid")) {
            return entry.id().toString();
        }
        if (type.equalsIgnoreCase("player") || type.equalsIgnoreCase("guild")) {
            return entry.displayName();
        }
        return "";
    }
}
