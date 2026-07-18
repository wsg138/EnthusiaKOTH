package com.enthusia.koth.infrastructure.config;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.config.PrivateTestingSettings;
import com.enthusia.koth.application.config.PluginSettings;
import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.CaptureLeaveBehavior;
import com.enthusia.koth.domain.CaptureZone;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.KothRegion;
import com.enthusia.koth.domain.LockState;
import com.enthusia.koth.domain.MaceRule;
import com.enthusia.koth.domain.Position;
import com.enthusia.koth.domain.rules.ItemRuleSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BukkitConfigurationService implements ConfigurationService {
    private static final int CURRENT_VERSION = 5;
    private final JavaPlugin plugin;
    private PluginSettings settings;
    private LockState lockState = LockState.UNLOCKED;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "JavaPlugin is the Bukkit-owned configuration source.")
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
        lockState = parseLockState(config.getString("locks.state", "UNLOCKED"));

        Map<KothFamily, ArenaDefinition> arenas = new EnumMap<>(KothFamily.class);
        Map<KothFamily, Boolean> enabledFamilies = new EnumMap<>(KothFamily.class);
        arenas.put(KothFamily.CAPTURE, loadArena(config, KothFamily.CAPTURE));
        enabledFamilies.put(KothFamily.CAPTURE, config.getBoolean("arenas.capture.enabled", true));
        arenas.put(KothFamily.MOVING, loadArena(config, KothFamily.MOVING));
        enabledFamilies.put(KothFamily.MOVING, config.getBoolean("arenas.moving.enabled", true));
        arenas.put(KothFamily.CONQUEST, loadArena(config, KothFamily.CONQUEST));
        enabledFamilies.put(KothFamily.CONQUEST, config.getBoolean("arenas.conquest.enabled", false));

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

        List<LocalTime> scheduleTimes = parseScheduleTimes(config.getStringList("schedule.times"));

        settings = new PluginSettings(
                config.getInt("config-version", CURRENT_VERSION),
                parseZoneId(config.getString("general.timezone", "America/New_York")),
                Duration.ofSeconds(config.getLong("general.manual-start-delay-seconds", 0)),
                lockState,
                config.getDouble("manual-start.basic-cost", 0.0),
                config.getDouble("manual-start.advanced-cost", 0.0),
                config.getBoolean("schedule.enabled", false),
                scheduleTimes,
                Duration.ofSeconds(Math.max(0, config.getLong("schedule.pre-start-warning-seconds", 300))),
                arenas,
                enabledFamilies,
                rules,
                soloRewards,
                guildRewards,
                config.getBoolean("discord.enabled", false),
                config.getString("discord.webhook-url", ""),
                new PrivateTestingSettings(
                        Duration.ofSeconds(Math.max(0, config.getLong("private-testing.lobby-seconds", 30))),
                        Duration.ofSeconds(Math.max(1, config.getLong("private-testing.quick-match-duration-seconds", 120))),
                        Math.max(1, config.getInt("private-testing.quick-capture-seconds", 15)),
                        config.getBoolean("private-testing.show-objective-particles", true)
                )
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

    @Override
    public void saveProtectedRegion(KothFamily family, Position first, Position second) {
        String base = "arenas." + family.key();
        plugin.getConfig().set(base + ".world", first.world());
        savePosition(base + ".protected-region.corner-1", first);
        savePosition(base + ".protected-region.corner-2", second);
        plugin.saveConfig();
        reload();
    }

    private void migrate(FileConfiguration config) {
        int version = config.getInt("config-version", 0);
        if (version < 2) {
            setIfAbsent(config, "private-testing.lobby-seconds", 30);
            setIfAbsent(config, "private-testing.quick-match-duration-seconds", 120);
            setIfAbsent(config, "private-testing.quick-capture-seconds", 15);
            setIfAbsent(config, "private-testing.show-objective-particles", true);
            setIfAbsent(config, "arenas.capture.enabled", true);
            setIfAbsent(config, "arenas.moving.enabled", true);
        }
        if (version < 3) {
            setIfAbsent(config, "schedule.pre-start-warning-seconds", 300);
        }
        if (version < 4) {
            double legacyRadius = config.getDouble("general.active-radius-blocks", 32.0);
            for (KothFamily family : KothFamily.values()) {
                setIfAbsent(config, "arenas." + family.key() + ".protected-radius", legacyRadius);
            }
        }
        if (version < 5) {
            for (KothFamily family : KothFamily.values()) {
                String base = "arenas." + family.key();
                double centerX = config.getDouble(base + ".center.x", 0.5);
                double centerY = config.getDouble(base + ".center.y", 80.0);
                double centerZ = config.getDouble(base + ".center.z", 0.5);
                double radius = config.getDouble(base + ".protected-radius", 32.0);
                setIfAbsent(config, base + ".protected-region.corner-1.x", centerX - radius);
                setIfAbsent(config, base + ".protected-region.corner-1.y", centerY - 64.0);
                setIfAbsent(config, base + ".protected-region.corner-1.z", centerZ - radius);
                setIfAbsent(config, base + ".protected-region.corner-2.x", centerX + radius);
                setIfAbsent(config, base + ".protected-region.corner-2.y", centerY + 64.0);
                setIfAbsent(config, base + ".protected-region.corner-2.z", centerZ + radius);
            }
        }
        if (version < CURRENT_VERSION) {
            config.set("config-version", CURRENT_VERSION);
            plugin.saveConfig();
        }
    }

    private void setIfAbsent(FileConfiguration config, String path, Object value) {
        if (!config.contains(path)) {
            config.set(path, value);
        }
    }

    private Position loadPosition(FileConfiguration config, String path, Position fallback,
                                  double fallbackX, double fallbackY, double fallbackZ) {
        return new Position(
                fallback.world(),
                config.getDouble(path + ".x", fallbackX),
                config.getDouble(path + ".y", fallbackY),
                config.getDouble(path + ".z", fallbackZ)
        );
    }

    private void savePosition(String path, Position position) {
        plugin.getConfig().set(path + ".x", position.x());
        plugin.getConfig().set(path + ".y", position.y());
        plugin.getConfig().set(path + ".z", position.z());
    }

    private ArenaDefinition loadArena(FileConfiguration config, KothFamily family) {
        String base = "arenas." + family.key();
        Position center = new Position(
                config.getString(base + ".world", "world"),
                config.getDouble(base + ".center.x", 0.5),
                config.getDouble(base + ".center.y", 80.0),
                config.getDouble(base + ".center.z", 0.5)
        );
        Position protectedFirst = loadPosition(config, base + ".protected-region.corner-1", center,
                center.x() - 32.0, center.y() - 64.0, center.z() - 32.0);
        Position protectedSecond = loadPosition(config, base + ".protected-region.corner-2", center,
                center.x() + 32.0, center.y() + 64.0, center.z() + 32.0);
        return new ArenaDefinition(
                family.key() + "-default",
                family,
                new KothRegion(family.key() + "-protected", protectedFirst, protectedSecond),
                new CaptureZone(family.key() + "-zone", center, config.getDouble(base + ".radius", 5.0)),
                config.getInt(base + ".duration-seconds", 900),
                config.getInt(base + ".capture-seconds", family == KothFamily.CAPTURE ? 120 : 0),
                parseCaptureLeaveBehavior(config.getString(base + ".leave-behavior", "RESET")),
                config.getDouble(base + ".decay-per-second", 1.0),
                config.getDouble(base + ".square-size", 20.0),
                config.getDouble(base + ".speed-blocks-per-second", 1.0)
        );
    }

    private ItemRuleSet loadRules(FileConfiguration config, KothFamily family) {
        String base = "rules.defaults." + family.key();
        return new ItemRuleSet(
                config.getBoolean(base + ".elytra", true),
                parseMaceRule(config.getString(base + ".mace", "FULLY_ALLOWED")),
                config.getBoolean(base + ".spear", true),
                config.getBoolean(base + ".ender-pearl", true),
                config.getBoolean(base + ".wind-charge", true),
                Duration.ofSeconds(Math.max(0, config.getLong(base + ".mace-cooldown-seconds", 0))),
                Duration.ofSeconds(Math.max(0, config.getLong(base + ".spear-cooldown-seconds", 0))),
                Duration.ofSeconds(Math.max(0, config.getLong(base + ".ender-pearl-cooldown-seconds", 0))),
                Duration.ofSeconds(Math.max(0, config.getLong(base + ".wind-charge-cooldown-seconds", 0)))
        );
    }

    private ZoneId parseZoneId(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            plugin.getLogger().warning("Invalid KOTH timezone '" + raw + "'. Falling back to America/New_York.");
            return ZoneId.of("America/New_York");
        }
    }

    private List<LocalTime> parseScheduleTimes(List<String> rawTimes) {
        List<LocalTime> parsed = new ArrayList<>();
        for (String raw : rawTimes) {
            try {
                parsed.add(LocalTime.parse(raw));
            } catch (DateTimeParseException ex) {
                plugin.getLogger().warning("Ignoring invalid KOTH schedule time '" + raw + "'. Expected HH:mm.");
            }
        }
        if (parsed.isEmpty()) {
            parsed.add(LocalTime.MIDNIGHT);
        }
        return List.copyOf(parsed);
    }

    private LockState parseLockState(String raw) {
        try {
            return LockState.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return LockState.UNLOCKED;
        }
    }

    private CaptureLeaveBehavior parseCaptureLeaveBehavior(String raw) {
        try {
            return CaptureLeaveBehavior.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return CaptureLeaveBehavior.RESET;
        }
    }

    private MaceRule parseMaceRule(String raw) {
        try {
            return MaceRule.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return MaceRule.FULLY_ALLOWED;
        }
    }
}
