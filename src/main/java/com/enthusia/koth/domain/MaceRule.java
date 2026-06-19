package com.enthusia.koth.domain;

public enum MaceRule {
    FULLY_DISABLED,
    BREACH_DISABLED,
    DENSITY_DISABLED,
    FULLY_ALLOWED;

    public MaceRule next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
