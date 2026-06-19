package com.enthusia.koth.infrastructure.storage;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.ports.ArenaRepository;
import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.KothFamily;

import java.util.Optional;

public final class ConfigArenaRepository implements ArenaRepository {
    private final ConfigurationService config;

    public ConfigArenaRepository(ConfigurationService config) {
        this.config = config;
    }

    @Override
    public Optional<ArenaDefinition> findDefault(KothFamily family) {
        return Optional.ofNullable(config.settings().arenas().get(family));
    }
}
