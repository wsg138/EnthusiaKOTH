package com.enthusia.koth.infrastructure.config;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.config.PluginSettings;
import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.CaptureLeaveBehavior;
import com.enthusia.koth.domain.CaptureZone;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.LockState;
import com.enthusia.koth.domain.MaceRule;
import com.enthusia.koth.domain.Position;
import com.enthusia.koth.domain.rules.ItemRuleSet;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BukkitConfigurationService implements ConfigurationService {
    private static final int CURRENT_VERSION = 1;
    private final JavaPlugin plugin;
    private PluginSettings settings;
    private LockState lockState = LockState.UNLOCKED;

    public BukkitConfigurationService(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        reload();
    }

    @Override
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        migrate(config);
        lockState = parseEnum(LockState.class, config.getString("locks.state", "UNLOCKED"), LockState.UNLOCKED);

        Map<KothFamily, ArenaDefinition> arenas = new EnumMap<>(KothFamily.class);
        arenas.put(KothFamily.CAPTURE, loadArena(config, KothFamily.CAPTURE));
        arenas.put(KothFamily.MOVING, loadArena(config, KothFamily.MOVING));
        arenas.put(KothFamily.CONQUEST, loadArena(config, KothFamily.CONQUEST));

        Map<KothFamily, ItemRuleSet> rules = new EnumMap<>(KothFamily.class);
        for (KothFamily family : KothFamily.values()) {
            rules.put(family, loadRules(config, family));
        }

        Map<KothFamily, Double> soloRewards = new EnumMap<>(KothFamily.class);
        Map<KothFamily, Double> guildRewards = new EnumMap<>(KothFamily.class);
        for (KothFamily family : KothFamily.values()) {
            soloRewards.put(family, config.getDouble("rewards." + family.key() + ".solo-vault-money", 0.0));
            guildRewards.put(family, config.getDouble("rewards." + family.key() + ".guild-vault-money", 0.0));
        }

        List<LocalTime> scheduleTimes = config.getStringList("schedule.times").stream()
                .map(LocalTime::parse)
                .toList();

        settings = new PluginSettings(
                config.getInt("config-version", CURRENT_VERSION),
                ZoneId.of(config.getString("general.timezone", "America/New_York")),
                Duration.ofMinutes(config.getLong("general.manual-start-block-before-scheduled-minutes", 45)),
                Duration.ofSeconds(config.getLong("general.manual-start-delay-seconds", 60)),
                config.getInt("general.active-radius-blocks", 96),
                lockState,
                config.getDouble("manual-start.basic-cost", 0.0),
                config.getDouble("manual-start.advanced-cost", 0.0),
                config.getBoolean("schedule.enabled", true),
                scheduleTimes,
                arenas,
                rules,
                soloRewards,
                guildRewards,
                config.getBoolean("discord.enabled", false),
                config.getString("discord.webhook-url", "")
        );
    }

    @Override
    public PluginSettings settings() {
        return settings;
    }

    @Override
    public void setLockState(LockState state) {
        lockState = state;
    }

    @Override
    public void saveLockState() {
        plugin.getConfig().set("locks.state", lockState.name());
        plugin.saveConfig();
        reload();
    }

    private void migrate(FileConfiguration config) {
        int version = config.getInt("config-version", 0);
        if (version < CURRENT_VERSION) {
            config.set("config-version", CURRENT_VERSION);
            plugin.saveConfig();
        }
    }

    private ArenaDefinition loadArena(FileConfiguration config, KothFamily family) {
        String base = "arenas." + family.key();
        Position center = new Position(
                config.getString(base + ".world", "world"),
                config.getDouble(base + ".center.x", 0.5),
                config.getDouble(base + ".center.y", 80.0),
                config.getDouble(base + ".center.z", 0.5)
        );
        return new ArenaDefinition(
                family.key() + "-default",
                family,
                new CaptureZone(family.key() + "-zone", center, config.getDouble(base + ".radius", 5.0)),
                config.getInt(base + ".duration-seconds", 900),
                config.getInt(base + ".capture-seconds", family == KothFamily.CAPTURE ? 120 : 0),
                parseEnum(CaptureLeaveBehavior.class, config.getString(base + ".leave-behavior", "RESET"), CaptureLeaveBehavior.RESET),
                config.getDouble(base + ".decay-per-second", 1.0),
                config.getDouble(base + ".square-size", 20.0),
                config.getDouble(base + ".speed-blocks-per-second", 1.0)
        );
    }

    private ItemRuleSet loadRules(FileConfiguration config, KothFamily family) {
        String base = "rules.defaults." + family.key();
        return new ItemRuleSet(
                config.getBoolean(base + ".elytra", true),
                parseEnum(MaceRule.class, config.getString(base + ".mace", "FULLY_ALLOWED"), MaceRule.FULLY_ALLOWED),
                config.getBoolean(base + ".spear", true),
                config.getBoolean(base + ".ender-pearl", true),
                config.getBoolean(base + ".wind-charge", true),
                Duration.ZERO,
                Duration.ZERO,
                Duration.ZERO,
                Duration.ZERO
        );
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
