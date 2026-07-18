package com.enthusia.koth.application.family;

import com.enthusia.koth.application.team.TeamResolver;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;

import java.time.Instant;
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
        event.objectivePosition(event.arena().zone().center());
    }

    @Override
    public TickResult tick(ActiveEvent event, Instant now) {
        Optional<TeamId> controller = ControllerSelector.singleController(
                ControllerSelector.teamsInZone(event, teamResolver));
        controller.ifPresentOrElse(team -> {
            event.currentController(team);
            event.addScore(team, 1.0);
        }, () -> event.currentController(null));
        return ControllerSelector.timeLimitOrRunning(event, now);
    }

    @Override
    public Optional<String> winnerDisplay(ActiveEvent event) {
        return WinnerSelector.winningTeamStorageKey(event);
    }
}
