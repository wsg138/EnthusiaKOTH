package com.enthusia.koth.application.manual;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.event.StartResult;
import com.enthusia.koth.application.ports.EconomyPort;
import com.enthusia.koth.application.ports.TransactionResult;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.EventKind;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.event.EventRequest;
import com.enthusia.koth.domain.rules.ItemRuleSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.UUID;

public final class ManualStartService {
    private final ConfigurationService config;
    private final ActiveEventService activeEvents;
    private final EconomyPort economy;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Application services are shared by dependency injection.")
    public ManualStartService(ConfigurationService config, ActiveEventService activeEvents, EconomyPort economy) {
        this.config = config;
        this.activeEvents = activeEvents;
        this.economy = economy;
    }

    public StartResult request(Player player, KothFamily family, TeamMode mode, boolean advanced) {
        double cost = startCost(advanced);
        StartResult payment = charge(player, cost);
        if (!payment.success()) {
            return payment;
        }

        StartResult result = activeEvents.requestStart(buildRequest(player, family, mode));
        if (!result.success() && cost > 0) {
            economy.deposit(player, cost, "Manual KOTH start refund");
        }
        return result;
    }

    private double startCost(boolean advanced) {
        return advanced ? config.settings().advancedStartCost() : config.settings().basicStartCost();
    }

    private StartResult charge(Player player, double cost) {
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
        return StartResult.success("Payment accepted.");
    }

    private EventRequest buildRequest(Player player, KothFamily family, TeamMode mode) {
        ItemRuleSet rules = config.settings().defaultRules().get(family);
        return new EventRequest(UUID.randomUUID(), family, mode, StartSource.MANUAL, player.getUniqueId(),
                Instant.now().plus(config.settings().manualStartDelay()), rules, false, EventKind.STANDARD, null, false);
    }
}
