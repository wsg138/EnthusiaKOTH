package com.enthusia.koth.infrastructure.command;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.lock.LockService;
import com.enthusia.koth.application.schedule.ScheduleService;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.LockState;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.event.EventRequest;
import com.enthusia.koth.infrastructure.gui.StartGuiService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class EkothCommand implements CommandExecutor, TabCompleter {
    private final ConfigurationService config;
    private final ActiveEventService activeEvents;
    private final ScheduleService schedule;
    private final LockService locks;
    private final StartGuiService gui;
    private final Runnable reloadAction;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Command adapter holds application services supplied by bootstrap.")
    public EkothCommand(ConfigurationService config, ActiveEventService activeEvents, ScheduleService schedule,
                        LockService locks, StartGuiService gui, Runnable reloadAction) {
        this.config = config;
        this.activeEvents = activeEvents;
        this.schedule = schedule;
        this.locks = locks;
        this.gui = gui;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/ekoth start | status | reload | lock <manual|all|off> | cancel"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(sender);
            case "startadmin" -> startAdmin(sender, args);
            case "status" -> status(sender);
            case "reload" -> {
                if (!requireAdmin(sender)) return true;
                reloadAction.run();
                sender.sendMessage(Component.text("EnthusiaKOTH reloaded."));
            }
            case "lock" -> lock(sender, args);
            case "cancel" -> {
                if (!requireAdmin(sender)) return true;
                activeEvents.cancelActive("admin");
                sender.sendMessage(Component.text("Active KOTH cancelled."));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand."));
        }
        return true;
    }

    private void start(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players should use the GUI. Console can use /ekoth startadmin <family> <solo|guild>."));
            return;
        }
        boolean advanced = player.hasPermission("enthusiakoth.start.advanced") || player.hasPermission("enthusiakoth.admin");
        boolean basic = player.hasPermission("enthusiakoth.start.basic") || advanced;
        if (!basic) {
            sender.sendMessage(Component.text("You do not have permission to start KOTH."));
            return;
        }
        gui.open(player, advanced);
    }

    private void startAdmin(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /ekoth startadmin <capture|moving|conquest> <solo|guild>"));
            return;
        }
        var family = KothFamily.fromKey(args[1]);
        if (family.isEmpty()) {
            sender.sendMessage(Component.text("Unknown KOTH family."));
            return;
        }
        TeamMode mode;
        try {
            mode = TeamMode.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text("Mode must be solo or guild."));
            return;
        }
        var result = activeEvents.requestStart(new EventRequest(UUID.randomUUID(), family.get(), mode, StartSource.ADMIN, null,
                Instant.now(), config.settings().defaultRules().get(family.get()), false));
        sender.sendMessage(Component.text(result.message()));
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component.text("Lock: " + locks.state()));
        sender.sendMessage(Component.text("Next scheduled: " + schedule.nextScheduledStart()));
        sender.sendMessage(Component.text("Queued: " + activeEvents.queuedCount()));
        sender.sendMessage(Component.text("Active: " + activeEvents.activeEvent().map(event -> event.request().family().key() + " " + event.state()).orElse("none")));
    }

    private void lock(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ekoth lock <manual|all|off>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "manual" -> locks.setState(LockState.MANUAL_LOCKED);
            case "all" -> locks.setState(LockState.ALL_LOCKED);
            case "off", "unlock" -> locks.setState(LockState.UNLOCKED);
            default -> {
                sender.sendMessage(Component.text("Usage: /ekoth lock <manual|all|off>"));
                return;
            }
        }
        sender.sendMessage(Component.text("Lock set to " + locks.state()));
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(Component.text("Missing permission: enthusiakoth.admin"));
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(List.of("start", "startadmin", "status", "reload", "lock", "cancel"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("startadmin")) {
            return filter(List.of("capture", "moving", "conquest"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("startadmin")) {
            return filter(List.of("solo", "guild"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lock")) {
            return filter(List.of("manual", "all", "off"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(value);
            }
        }
        return out;
    }
}
