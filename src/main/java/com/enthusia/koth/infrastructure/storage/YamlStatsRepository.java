package com.enthusia.koth.infrastructure.storage;

import com.enthusia.koth.application.ports.LeaderboardEntry;
import com.enthusia.koth.application.ports.StatsRepository;
import com.enthusia.koth.domain.KothFamily;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class YamlStatsRepository implements StatsRepository {
    private final File file;
    private final Logger logger;
    private YamlConfiguration yaml;

    public YamlStatsRepository(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        this.logger = plugin.getLogger();
        reload();
    }

    @Override
    public synchronized void incrementPlayerWin(UUID playerId, String lastKnownName, KothFamily family) {
        increment("players", playerId, lastKnownName, family);
    }

    @Override
    public synchronized void incrementGuildWin(UUID guildId, String displayName, KothFamily family) {
        increment("guilds", guildId, displayName, family);
    }

    @Override
    public synchronized List<LeaderboardEntry> topPlayers(Optional<KothFamily> family, int limit) {
        return top("players", family, limit);
    }

    @Override
    public synchronized List<LeaderboardEntry> topGuilds(Optional<KothFamily> family, int limit) {
        return top("guilds", family, limit);
    }

    @Override
    public synchronized void save() {
        try {
            yaml.save(file);
        } catch (IOException ex) {
            logger.warning("Failed to save KOTH stats: " + ex.getMessage());
        }
    }

    @Override
    public synchronized void reload() {
        yaml = YamlConfiguration.loadConfiguration(file);
        if (!file.exists()) {
            save();
        }
    }

    private void increment(String root, UUID id, String displayName, KothFamily family) {
        String path = root + "." + id;
        yaml.set(path + ".name", displayName);
        yaml.set(path + "." + family.key(), yaml.getInt(path + "." + family.key(), 0) + 1);
        yaml.set(path + ".all", yaml.getInt(path + ".all", 0) + 1);
        save();
    }

    private List<LeaderboardEntry> top(String root, Optional<KothFamily> family, int limit) {
        ConfigurationSection section = yaml.getConfigurationSection(root);
        if (section == null) {
            return List.of();
        }
        String key = family.map(KothFamily::key).orElse("all");
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (String idText : section.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(idText);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            int wins = yaml.getInt(root + "." + idText + "." + key, 0);
            if (wins > 0) {
                entries.add(new LeaderboardEntry(id, yaml.getString(root + "." + idText + ".name", idText), wins));
            }
        }
        return entries.stream()
                .sorted(Comparator.comparingInt(LeaderboardEntry::wins).reversed())
                .limit(limit)
                .toList();
    }
}
