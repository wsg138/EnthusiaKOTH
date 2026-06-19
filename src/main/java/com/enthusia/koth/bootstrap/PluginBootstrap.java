package com.enthusia.koth.bootstrap;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.family.CaptureFamilyHandler;
import com.enthusia.koth.application.family.ConquestFamilyHandler;
import com.enthusia.koth.application.family.KothFamilyHandler;
import com.enthusia.koth.application.family.MovingFamilyHandler;
import com.enthusia.koth.application.lock.LockService;
import com.enthusia.koth.application.manual.ManualStartService;
import com.enthusia.koth.application.ports.AnnouncementPort;
import com.enthusia.koth.application.ports.ArenaRepository;
import com.enthusia.koth.application.ports.CombatIntegrationPort;
import com.enthusia.koth.application.ports.DisplayPort;
import com.enthusia.koth.application.ports.GuildPort;
import com.enthusia.koth.application.ports.StatsRepository;
import com.enthusia.koth.application.reward.RewardService;
import com.enthusia.koth.application.rules.RestrictionService;
import com.enthusia.koth.application.schedule.ScheduleService;
import com.enthusia.koth.application.team.TeamResolver;
import com.enthusia.koth.infrastructure.command.EkothCommand;
import com.enthusia.koth.infrastructure.config.BukkitConfigurationService;
import com.enthusia.koth.infrastructure.display.BukkitAnnouncementAdapter;
import com.enthusia.koth.infrastructure.display.BukkitDisplayAdapter;
import com.enthusia.koth.infrastructure.display.CompositeAnnouncementPort;
import com.enthusia.koth.infrastructure.display.DiscordStatusAdapter;
import com.enthusia.koth.infrastructure.gui.StartGuiService;
import com.enthusia.koth.infrastructure.integration.CombatXAdapter;
import com.enthusia.koth.infrastructure.integration.LumaGuildsAdapter;
import com.enthusia.koth.infrastructure.integration.VaultEconomyAdapter;
import com.enthusia.koth.infrastructure.listener.RestrictionListener;
import com.enthusia.koth.infrastructure.placeholder.EnthusiaKothExpansion;
import com.enthusia.koth.infrastructure.storage.ConfigArenaRepository;
import com.enthusia.koth.infrastructure.storage.YamlStatsRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class PluginBootstrap {
    private final JavaPlugin plugin;
    private ConfigurationService config;
    private ActiveEventService activeEvents;
    private ScheduleService schedule;
    private LockService locks;
    private StatsRepository stats;
    private RestrictionService restrictions;
    private VaultEconomyAdapter economy;
    private StartGuiService gui;
    private EnthusiaKothExpansion expansion;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "JavaPlugin is the Bukkit-owned lifecycle object for this bootstrap.")
    public PluginBootstrap(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        config = new BukkitConfigurationService(plugin);
        stats = new YamlStatsRepository(plugin);
        economy = new VaultEconomyAdapter(plugin);
        GuildPort guilds = new LumaGuildsAdapter();
        CombatIntegrationPort combat = new CombatXAdapter();
        ArenaRepository arenas = new ConfigArenaRepository(config);
        locks = new LockService(config);
        restrictions = new RestrictionService();
        TeamResolver teams = new TeamResolver(guilds);
        List<KothFamilyHandler> handlers = List.of(
                new CaptureFamilyHandler(teams),
                new MovingFamilyHandler(teams),
                new ConquestFamilyHandler(teams)
        );
        RewardService rewards = new RewardService(config, economy, guilds, stats, plugin.getLogger());
        DisplayPort display = new BukkitDisplayAdapter();
        AnnouncementPort announcements = new CompositeAnnouncementPort(List.of(
                new BukkitAnnouncementAdapter(),
                new DiscordStatusAdapter(config, plugin.getLogger())
        ));
        activeEvents = new ActiveEventService(arenas, locks, rewards, announcements, display, handlers, plugin.getLogger());
        schedule = new ScheduleService(config, activeEvents);
        ManualStartService manualStart = new ManualStartService(config, schedule, activeEvents, economy);
        gui = new StartGuiService(manualStart);

        activeEvents.attachTask(Bukkit.getScheduler().runTaskTimer(plugin, activeEvents::tick, 20L, 20L));
        schedule.attachTask(Bukkit.getScheduler().runTaskTimer(plugin, schedule::tick, 20L, 20L));
        Bukkit.getPluginManager().registerEvents(gui, plugin);
        Bukkit.getPluginManager().registerEvents(new RestrictionListener(activeEvents, restrictions, combat), plugin);
        registerCommand();
        registerPlaceholderApi();
    }

    public void disable() {
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        if (activeEvents != null) {
            activeEvents.shutdown();
        }
        if (schedule != null) {
            schedule.shutdown();
        }
        if (stats != null) {
            stats.save();
        }
        if (restrictions != null) {
            restrictions.clear();
        }
    }

    private void reload() {
        config.reload();
        locks.reload();
        schedule.reload();
        stats.reload();
        economy.reload();
        restrictions.clear();
    }

    private void registerCommand() {
        PluginCommand command = plugin.getCommand("ekoth");
        if (command == null) {
            throw new IllegalStateException("Command /ekoth is missing from plugin.yml");
        }
        EkothCommand executor = new EkothCommand(config, activeEvents, schedule, locks, gui, this::reload);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerPlaceholderApi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        expansion = new EnthusiaKothExpansion(stats);
        expansion.register();
    }
}
