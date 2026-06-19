package com.enthusia.koth.application.family;

public record TickResult(boolean finished, String reason) {
    public static TickResult running() {
        return new TickResult(false, "");
    }

    public static TickResult finished(String reason) {
        return new TickResult(true, reason);
    }
}
