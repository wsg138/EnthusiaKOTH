package com.enthusia.koth.application.rules;

import java.time.Duration;

public record RestrictionDecision(boolean allowed, String message, Duration cooldown) {
    public static RestrictionDecision allowed(Duration cooldown) {
        return new RestrictionDecision(true, "", cooldown);
    }

    public static RestrictionDecision denied(String message) {
        return new RestrictionDecision(false, message, Duration.ZERO);
    }
}
