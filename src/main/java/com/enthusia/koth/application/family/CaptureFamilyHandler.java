package com.enthusia.koth.application.family;

import com.enthusia.koth.application.team.TeamResolver;
import com.enthusia.koth.domain.CaptureLeaveBehavior;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.util.List;
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
        List<TeamId> controllingTeams = Bukkit.getOnlinePlayers().stream()
                .filter(player -> event.arena().zone().contains(player.getLocation()))
                .map(player -> teamResolver.resolve(player, event.request().teamMode()))
                .flatMap(Optional::stream)
                .distinct()
                .toList();

        if (controllingTeams.size() == 1) {
            TeamId controller = controllingTeams.getFirst();
            event.currentController(controller);
            double next = event.scores().getOrDefault(controller, 0.0) + 1.0;
            event.setScore(controller, Math.min(event.arena().captureSeconds(), next));
            if (next >= event.arena().captureSeconds()) {
                return TickResult.finished("capture-complete");
            }
        } else if (event.currentController().isPresent()) {
            TeamId previous = event.currentController().get();
            if (event.arena().leaveBehavior() == CaptureLeaveBehavior.RESET) {
                event.setScore(previous, 0.0);
            } else if (event.arena().leaveBehavior() == CaptureLeaveBehavior.DECAY) {
                double decayed = Math.max(0.0, event.scores().getOrDefault(previous, 0.0) - event.arena().decayPerSecond());
                event.setScore(previous, decayed);
            }
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
