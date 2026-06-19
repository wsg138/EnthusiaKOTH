package com.enthusia.koth.application.family;

import com.enthusia.koth.application.team.TeamResolver;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class ConquestFamilyHandler implements KothFamilyHandler {
    private final TeamResolver teamResolver;

    public ConquestFamilyHandler(TeamResolver teamResolver) {
        this.teamResolver = teamResolver;
    }

    @Override
    public KothFamily family() {
        return KothFamily.CONQUEST;
    }

    @Override
    public void start(ActiveEvent event) {
        event.clearScores();
    }

    @Override
    public TickResult tick(ActiveEvent event, Instant now) {
        List<TeamId> controllers = Bukkit.getOnlinePlayers().stream()
                .filter(player -> event.arena().zone().contains(player.getLocation()))
                .map(player -> teamResolver.resolve(player, event.request().teamMode()))
                .flatMap(Optional::stream)
                .distinct()
                .toList();

        if (controllers.size() == 1) {
            TeamId controller = controllers.getFirst();
            event.currentController(controller);
            event.addScore(controller, 1.0);
        } else {
            event.currentController(null);
        }

        if (!now.isBefore(event.endsAt())) {
            return TickResult.finished("time-limit");
        }
        return TickResult.running();
    }

    @Override
    public Optional<String> winnerDisplay(ActiveEvent event) {
        return WinnerSelector.winningTeamStorageKey(event);
    }
}
