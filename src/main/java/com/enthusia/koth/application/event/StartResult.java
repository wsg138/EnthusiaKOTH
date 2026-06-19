package com.enthusia.koth.application.event;

public record StartResult(boolean success, String message) {
    public static StartResult success(String message) {
        return new StartResult(true, message);
    }

    public static StartResult failure(String message) {
        return new StartResult(false, message);
    }
}
