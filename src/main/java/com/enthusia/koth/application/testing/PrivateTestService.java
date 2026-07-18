package com.enthusia.koth.application.testing;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.event.StartResult;
import com.enthusia.koth.domain.EventKind;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.PrivateTestAccess;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.event.EventRequest;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.UUID;

public final class PrivateTestService {
    private final ConfigurationService configuration;
    private final ActiveEventService activeEvents;

    public PrivateTestService(ConfigurationService configuration, ActiveEventService activeEvents) {
        this.configuration = configuration;
        this.activeEvents = activeEvents;
    }

    public StartResult start(Player owner, KothFamily family, TeamMode teamMode, PrivateTestAccess access, boolean quickTiming) {
        if (family == KothFamily.CONQUEST) {
            return StartResult.failure("Conquest is not available for private testing yet.");
        }
        Instant startAt = Instant.now().plus(configuration.settings().privateTesting().lobbyDuration());
        EventRequest request = new EventRequest(
                UUID.randomUUID(), family, teamMode, StartSource.PRIVATE_TEST, owner.getUniqueId(), startAt,
                configuration.settings().defaultRules().get(family), false, EventKind.PRIVATE_TEST, access, quickTiming
        );
        return activeEvents.requestStart(request);
    }

    public StartResult join(Player player) {
        return activeEvents.joinPrivateTest(player);
    }

    public StartResult leave(Player player) {
        return activeEvents.leavePrivateTest(player);
    }

    public StartResult cancel(Player player) {
        return activeEvents.cancelPrivateTest(player);
    }
}
