package com.enthusia.koth.application.ports;

import java.util.UUID;

public record LeaderboardEntry(UUID id, String displayName, int wins) {
}
