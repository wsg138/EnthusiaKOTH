package com.enthusia.koth.application.family;

import com.enthusia.koth.application.team.TeamResolver;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.Position;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class MovingFamilyHandler implements KothFamilyHandler {
    private final TeamResolver teamResolver;

    public MovingFamilyHandler(TeamResolver teamResolver) {
        this.teamResolver = teamResolver;
    }

    @Override
    public KothFamily family() {
        return KothFamily.MOVING;
    }

    @Override
    public void start(ActiveEvent event) {
        event.clearScores();
    }

    @Override
    public TickResult tick(ActiveEvent event, Instant now) {
        Location point = movingPoint(event, now);
        double radius = event.arena().zone().radius();
        List<TeamId> controllers = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.getWorld().equals(point.getWorld()))
                .filter(player -> player.getLocation().distanceSquared(point) <= radius * radius)
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

    public Location movingPoint(ActiveEvent event, Instant now) {
        Position center = event.arena().zone().center();
        double half = event.arena().movingSquareSize() / 2.0;
        double perimeter = event.arena().movingSquareSize() * 4.0;
        double elapsed = Math.max(0, Duration.between(event.startsAt(), now).toMillis() / 1000.0);
        double distance = elapsed * event.arena().movingSpeedBlocksPerSecond() % perimeter;
        double x = center.x();
        double z = center.z();
        if (distance <= half) {
            z -= distance;
        } else if (distance <= half + event.arena().movingSquareSize()) {
            z -= half;
            x += distance - half;
        } else if (distance <= half + (event.arena().movingSquareSize() * 2.0)) {
            x += half;
            z = center.z() - half + (distance - half - event.arena().movingSquareSize());
        } else if (distance <= half + (event.arena().movingSquareSize() * 3.0)) {
            z += half;
            x = center.x() + half - (distance - half - (event.arena().movingSquareSize() * 2.0));
        } else {
            x -= half;
            z = center.z() + half - (distance - half - (event.arena().movingSquareSize() * 3.0));
        }
        return new Location(center.toLocation().getWorld(), x, center.y(), z);
    }

    @Override
    public Optional<String> winnerDisplay(ActiveEvent event) {
        return WinnerSelector.winningTeamStorageKey(event);
    }
}
