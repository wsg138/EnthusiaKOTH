package com.enthusia.koth.application.manual;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.event.StartResult;
import com.enthusia.koth.application.ports.EconomyPort;
import com.enthusia.koth.application.ports.TransactionResult;
import com.enthusia.koth.application.schedule.ScheduleService;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.event.EventRequest;
import com.enthusia.koth.domain.rules.ItemRuleSet;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class ManualStartService {
    private final ConfigurationService config;
    private final ScheduleService schedule;
    private final ActiveEventService activeEvents;
    private final EconomyPort economy;

    public ManualStartService(ConfigurationService config, ScheduleService schedule, ActiveEventService activeEvents, EconomyPort economy) {
        this.config = config;
        this.schedule = schedule;
        this.activeEvents = activeEvents;
        this.economy = economy;
    }

    public StartResult request(Player player, KothFamily family, TeamMode mode, boolean advanced) {
        Instant nextScheduled = schedule.nextScheduledStart();
        Duration until = Duration.between(Instant.now(), nextScheduled);
        if (!until.isNegative() && until.compareTo(config.settings().manualBlockBeforeScheduled()) <= 0) {
            return StartResult.failure("Manual KOTH cannot start within " + config.settings().manualBlockBeforeScheduled().toMinutes() + " minutes of the next scheduled KOTH.");
        }
        double cost = advanced ? config.settings().advancedStartCost() : config.settings().basicStartCost();
        if (cost > 0) {
            if (!economy.isAvailable()) {
                return StartResult.failure("Economy is unavailable.");
            }
            if (!economy.has(player, cost)) {
                return StartResult.failure("You need $" + cost + " to start this KOTH.");
            }
            TransactionResult paid = economy.withdraw(player, cost, "Manual KOTH start");
            if (!paid.success()) {
                return StartResult.failure(paid.message());
            }
        }
        ItemRuleSet rules = config.settings().defaultRules().get(family);
        EventRequest request = new EventRequest(UUID.randomUUID(), family, mode, StartSource.MANUAL, player.getUniqueId(),
                Instant.now().plus(config.settings().manualStartDelay()), rules, false);
        StartResult result = activeEvents.requestStart(request);
        if (!result.success() && cost > 0) {
            economy.deposit(player, cost, "Manual KOTH start refund");
        }
        return result;
    }
}
