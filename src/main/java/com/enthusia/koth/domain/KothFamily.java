package com.enthusia.koth.domain;

import java.util.Locale;
import java.util.Optional;

public enum KothFamily {
    CAPTURE("capture"),
    MOVING("moving"),
    CONQUEST("conquest");

    private final String key;

    KothFamily(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<KothFamily> fromKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        for (KothFamily family : values()) {
            if (family.key.equals(normalized)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }
}
