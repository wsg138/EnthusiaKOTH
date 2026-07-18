package com.enthusia.koth.application.family;

import com.enthusia.koth.application.team.TeamResolver;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class ControllerSelector {
    private ControllerSelector() {
    }

    static List<TeamId> teamsInZone(ActiveEvent event, TeamResolver teamResolver) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> event.isParticipant(player.getUniqueId()))
                .filter(player -> event.arena().zone().contains(player.getLocation()))
                .map(player -> teamResolver.resolve(player, event.request().teamMode()))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    static List<TeamId> teamsAtPoint(ActiveEvent event, TeamResolver teamResolver, Location point) {
        double radius = event.arena().zone().radius();
        double radiusSquared = radius * radius;
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> event.isParticipant(player.getUniqueId()))
                .filter(player -> player.getWorld().equals(point.getWorld()))
                .filter(player -> player.getLocation().distanceSquared(point) <= radiusSquared)
                .map(player -> teamResolver.resolve(player, event.request().teamMode()))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    static Optional<TeamId> singleController(List<TeamId> teams) {
        return teams.size() == 1 ? Optional.of(teams.getFirst()) : Optional.empty();
    }

    static TickResult timeLimitOrRunning(ActiveEvent event, Instant now) {
        return !now.isBefore(event.endsAt())
                ? TickResult.finished("time-limit")
                : TickResult.running();
    }
}
