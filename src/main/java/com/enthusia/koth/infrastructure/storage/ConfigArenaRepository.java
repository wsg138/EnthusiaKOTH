package com.enthusia.koth.infrastructure.storage;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.ports.ArenaRepository;
import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.KothFamily;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Optional;

public final class ConfigArenaRepository implements ArenaRepository {
    private final ConfigurationService config;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Repository adapter reads the shared runtime configuration service.")
    public ConfigArenaRepository(ConfigurationService config) {
        this.config = config;
    }

    @Override
    public Optional<ArenaDefinition> findDefault(KothFamily family) {
        return Optional.ofNullable(config.settings().arenas().get(family));
    }
}
