package com.enthusia.koth.application.ports;

public record TransactionResult(boolean success, String message) {
    public static TransactionResult success(String message) {
        return new TransactionResult(true, message);
    }

    public static TransactionResult failure(String message) {
        return new TransactionResult(false, message);
    }
}
