package com.enthusia.koth.application.config;

import java.time.Duration;

public record PrivateTestingSettings(
        Duration lobbyDuration,
        Duration quickMatchDuration,
        int quickCaptureSeconds,
        boolean showObjectiveParticles
) {
}
