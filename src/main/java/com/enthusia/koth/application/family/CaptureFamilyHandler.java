package com.enthusia.koth.application.family;

import com.enthusia.koth.application.team.TeamResolver;
import com.enthusia.koth.domain.CaptureLeaveBehavior;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
        event.scores().clear();
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
        Optional<Map.Entry<TeamId, Double>> first = event.scores().entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue));
        if (first.isEmpty() || first.get().getValue() <= 0.0) {
            return Optional.empty();
        }
        double winningScore = first.get().getValue();
        long tied = event.scores().values().stream().filter(score -> Double.compare(score, winningScore) == 0).count();
        if (tied > 1) {
            return Optional.empty();
        }
        return Optional.of(first.get().getKey().storageKey());
    }
}
