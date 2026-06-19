package com.enthusia.koth.application.family;

import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.team.TeamId;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

final class WinnerSelector {
    private WinnerSelector() {
    }

    static Optional<String> winningTeamStorageKey(ActiveEvent event) {
        Optional<Map.Entry<TeamId, Double>> leader = event.scores().entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue));
        if (leader.isEmpty() || leader.get().getValue() <= 0.0) {
            return Optional.empty();
        }
        double winningScore = leader.get().getValue();
        long tied = event.scores().values().stream()
                .filter(score -> Double.compare(score, winningScore) == 0)
                .count();
        if (tied > 1) {
            return Optional.empty();
        }
        return Optional.of(leader.get().getKey().storageKey());
    }
}
