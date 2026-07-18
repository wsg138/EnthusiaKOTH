package com.enthusia.koth.domain;

public record ArenaDefinition(
        String id,
        KothFamily family,
        KothRegion protectedRegion,
        CaptureZone zone,
        int durationSeconds,
        int captureSeconds,
        CaptureLeaveBehavior leaveBehavior,
        double decayPerSecond,
        double movingSquareSize,
        double movingSpeedBlocksPerSecond
) {
}
