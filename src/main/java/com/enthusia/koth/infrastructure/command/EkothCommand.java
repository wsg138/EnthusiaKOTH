package com.enthusia.koth.infrastructure.command;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.lock.LockService;
import com.enthusia.koth.application.schedule.ScheduleService;
import com.enthusia.koth.application.setup.ArenaSetupService;
import com.enthusia.koth.application.testing.PrivateTestService;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.EventKind;
import com.enthusia.koth.domain.PrivateTestAccess;
import com.enthusia.koth.domain.Position;
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
import org.bukkit.Bukkit;

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
    private final PrivateTestService privateTests;
    private final ArenaSetupService arenaSetup;
    private final Runnable reloadAction;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Command adapter holds application services supplied by bootstrap.")
    public EkothCommand(ConfigurationService config, ActiveEventService activeEvents, ScheduleService schedule,
                        LockService locks, StartGuiService gui, PrivateTestService privateTests, ArenaSetupService arenaSetup,
                        Runnable reloadAction) {
        this.config = config;
        this.activeEvents = activeEvents;
        this.schedule = schedule;
        this.locks = locks;
        this.gui = gui;
        this.privateTests = privateTests;
        this.arenaSetup = arenaSetup;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/ekoth start | test | arena | status | doctor | reload | lock <manual|all|off> | cancel"));
            return true;
        }
        handleSubcommand(sender, args);
        return true;
    }

    private void handleSubcommand(CommandSender sender, String[] args) {
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(sender);
            case "startadmin" -> startAdmin(sender, args);
            case "test" -> test(sender, args);
            case "arena" -> arena(sender, args);
            case "status" -> status(sender);
            case "doctor" -> doctor(sender);
            case "reload" -> reload(sender);
            case "lock" -> lock(sender, args);
            case "cancel" -> cancel(sender);
            default -> sender.sendMessage(Component.text("Unknown subcommand."));
        }
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
                Instant.now(), config.settings().defaultRules().get(family.get()), false, EventKind.STANDARD, null, false));
        sender.sendMessage(Component.text(result.message()));
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component.text("Lock: " + locks.state()));
        sender.sendMessage(Component.text("Next scheduled: " + schedule.nextScheduledStart()));
        sender.sendMessage(Component.text("Queued: " + activeEvents.queuedCount()));
        String active = activeEvents.activeEvent()
                .filter(event -> !event.isPrivateTest() || sender instanceof Player player && event.isParticipant(player.getUniqueId()))
                .map(event -> event.request().family().key() + " " + event.state())
                .orElse("none");
        sender.sendMessage(Component.text("Active: " + active));
    }

    private void doctor(CommandSender sender) {
        if (!requireAdmin(sender)) return;
        boolean healthy = true;
        for (KothFamily family : List.of(KothFamily.CAPTURE, KothFamily.MOVING)) {
            var arena = config.settings().arenas().get(family);
            if (arena == null || Bukkit.getWorld(arena.zone().center().world()) == null) {
                healthy = false;
                sender.sendMessage(Component.text("FAIL: " + family.key() + " world is not loaded."));
            } else {
                sender.sendMessage(Component.text("OK: " + family.key() + " arena world is loaded."));
            }
        }
        sender.sendMessage(Component.text("INFO: KOTH does not modify combat, item, or combat-tag movement rules."));
        sender.sendMessage(Component.text(healthy ? "KOTH preflight passed." : "KOTH preflight has blocking issues."));
    }

    private void test(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Private KOTH tests must be started by a player."));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("/ekoth test start <capture|moving> <solo|guild> <self|staff> [quick|production]"));
            player.sendMessage(Component.text("/ekoth test join | leave | cancel"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> startTest(player, args);
            case "join" -> joinTest(player);
            case "leave" -> player.sendMessage(Component.text(privateTests.leave(player).message()));
            case "cancel" -> player.sendMessage(Component.text(privateTests.cancel(player).message()));
            default -> player.sendMessage(Component.text("Unknown private test command."));
        }
    }

    private void startTest(Player player, String[] args) {
        if (!player.hasPermission("enthusiakoth.test.start") && !player.hasPermission("enthusiakoth.admin")) {
            player.sendMessage(Component.text("Missing permission: enthusiakoth.test.start"));
            return;
        }
        if (args.length < 5) {
            player.sendMessage(Component.text("Usage: /ekoth test start <capture|moving> <solo|guild> <self|staff> [quick|production]"));
            return;
        }
        var family = KothFamily.fromKey(args[2]).filter(value -> value != KothFamily.CONQUEST);
        if (family.isEmpty()) {
            player.sendMessage(Component.text("Private testing currently supports capture and moving."));
            return;
        }
        TeamMode teamMode = parseTeamMode(args[3]);
        if (teamMode == null) {
            player.sendMessage(Component.text("Mode must be solo or guild."));
            return;
        }
        PrivateTestAccess access = switch (args[4].toLowerCase(Locale.ROOT)) {
            case "self" -> PrivateTestAccess.OWNER_ONLY;
            case "staff" -> PrivateTestAccess.PERMISSION_JOIN;
            default -> null;
        };
        if (access == null) {
            player.sendMessage(Component.text("Access must be self or staff."));
            return;
        }
        boolean quickTiming = true;
        if (args.length == 6) {
            if (args[5].equalsIgnoreCase("production")) {
                quickTiming = false;
            } else if (!args[5].equalsIgnoreCase("quick")) {
                player.sendMessage(Component.text("Timing must be quick or production."));
                return;
            }
        }
        player.sendMessage(Component.text(privateTests.start(player, family.get(), teamMode, access, quickTiming).message()));
    }

    private void joinTest(Player player) {
        if (!player.hasPermission("enthusiakoth.test.join") && !player.hasPermission("enthusiakoth.admin")) {
            player.sendMessage(Component.text("Missing permission: enthusiakoth.test.join"));
            return;
        }
        player.sendMessage(Component.text(privateTests.join(player).message()));
    }

    private void arena(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Arena setup must be run by a player."));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("/ekoth arena corner1|corner2 <capture|moving|conquest>"));
            player.sendMessage(Component.text("/ekoth arena setregion <family> <x1> <y1> <z1> <x2> <y2> <z2>"));
            return;
        }
        var family = KothFamily.fromKey(args[2]);
        if (family.isEmpty()) {
            player.sendMessage(Component.text("Unknown KOTH family."));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "corner1", "corner2" -> saveCorner(player, family.get(), args[1].equalsIgnoreCase("corner1"));
            case "setregion" -> setRegion(player, family.get(), args);
            default -> player.sendMessage(Component.text("Usage: /ekoth arena corner1|corner2 <family>"));
        }
    }

    private void saveCorner(Player player, KothFamily family, boolean firstCorner) {
        var existing = config.settings().arenas().get(family).protectedRegion();
        Position position = new Position(player.getWorld().getName(), player.getLocation().getBlockX(),
                player.getLocation().getBlockY(), player.getLocation().getBlockZ());
        Position first = firstCorner ? position : new Position(position.world(), existing.first().x(), existing.first().y(), existing.first().z());
        Position second = firstCorner ? new Position(position.world(), existing.second().x(), existing.second().y(), existing.second().z()) : position;
        player.sendMessage(Component.text(arenaSetup.saveProtectedRegion(family, first, second).message()));
    }

    private void setRegion(Player player, KothFamily family, String[] args) {
        if (args.length != 9) {
            player.sendMessage(Component.text("Usage: /ekoth arena setregion <family> <x1> <y1> <z1> <x2> <y2> <z2>"));
            return;
        }
        try {
            Position first = new Position(player.getWorld().getName(), Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
            Position second = new Position(player.getWorld().getName(), Double.parseDouble(args[6]), Double.parseDouble(args[7]), Double.parseDouble(args[8]));
            player.sendMessage(Component.text(arenaSetup.saveProtectedRegion(family, first, second).message()));
        } catch (NumberFormatException ex) {
            player.sendMessage(Component.text("Protected-region coordinates must be valid numbers."));
        }
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

    private void reload(CommandSender sender) {
        if (!requireAdmin(sender)) return;
        reloadAction.run();
        sender.sendMessage(Component.text("EnthusiaKOTH reloaded."));
    }

    private void cancel(CommandSender sender) {
        if (!requireAdmin(sender)) return;
        activeEvents.cancelActive("admin");
        sender.sendMessage(Component.text("Active KOTH cancelled."));
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
            return filter(List.of("start", "startadmin", "test", "arena", "status", "doctor", "reload", "lock", "cancel"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return filter(List.of("start", "join", "leave", "cancel"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("test") && args[1].equalsIgnoreCase("start")) {
            return filter(List.of("capture", "moving"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("test") && args[1].equalsIgnoreCase("start")) {
            return filter(List.of("solo", "guild"), args[3]);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("test") && args[1].equalsIgnoreCase("start")) {
            return filter(List.of("self", "staff"), args[4]);
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("test") && args[1].equalsIgnoreCase("start")) {
            return filter(List.of("quick", "production"), args[5]);
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
        if (args.length == 2 && args[0].equalsIgnoreCase("arena")) {
            return filter(List.of("corner1", "corner2", "setregion"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("arena")) {
            return filter(List.of("capture", "moving", "conquest"), args[2]);
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

    private TeamMode parseTeamMode(String raw) {
        try {
            return TeamMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

}
