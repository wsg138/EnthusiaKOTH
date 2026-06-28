package com.enthusia.koth.domain.team;

import com.enthusia.koth.domain.TeamMode;

import java.util.Locale;
import java.util.UUID;

public record TeamId(TeamMode mode, UUID id) {
    public String storageKey() {
        return mode.name().toLowerCase(Locale.ROOT) + ":" + id;
    }
}
