package com.enthusia.koth.application.family;

import com.enthusia.koth.application.team.TeamResolver;
import com.enthusia.koth.domain.CaptureLeaveBehavior;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;

import java.time.Instant;
import java.util.Optional;

public final class CaptureFamilyHandler implements KothFamilyHandler {
    private final TeamResolver teamResolver;

    public CaptureFamilyHandler(TeamResolver teamResolver) {
        this.teamResolver = teamResolver;
    }

    @Override
    public KothFamily family() {
        return KothFamily.CAPTURE;
    }

    @Override
    public void start(ActiveEvent event) {
        event.clearScores();
    }

    @Override
    public TickResult tick(ActiveEvent event, Instant now) {
        Optional<TeamId> controller = ControllerSelector.singleController(
                ControllerSelector.teamsInZone(event, teamResolver));
        if (controller.isPresent() && capture(event, controller.get())) {
            return TickResult.finished("capture-complete");
        }
        controller.ifPresentOrElse(event::currentController, () -> clearController(event));
        return ControllerSelector.timeLimitOrRunning(event, now);
    }

    private boolean capture(ActiveEvent event, TeamId controller) {
        double next = event.scores().getOrDefault(controller, 0.0) + 1.0;
        event.setScore(controller, Math.min(event.arena().captureSeconds(), next));
        return next >= event.arena().captureSeconds();
    }

    private void clearController(ActiveEvent event) {
        event.currentController().ifPresent(previous -> {
            if (event.arena().leaveBehavior() == CaptureLeaveBehavior.RESET) {
                event.setScore(previous, 0.0);
            } else if (event.arena().leaveBehavior() == CaptureLeaveBehavior.DECAY) {
                double nextScore = event.scores().getOrDefault(previous, 0.0) - event.arena().decayPerSecond();
                event.setScore(previous, Math.max(0.0, nextScore));
            }
            event.currentController(null);
        });
    }

    @Override
    public Optional<String> winnerDisplay(ActiveEvent event) {
        return WinnerSelector.winningTeamStorageKey(event);
    }
}
