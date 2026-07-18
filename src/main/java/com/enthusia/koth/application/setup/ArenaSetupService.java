package com.enthusia.koth.application.setup;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.StartResult;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.Position;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class ArenaSetupService {
    private final ConfigurationService configuration;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Configuration service is shared by bootstrap.")
    public ArenaSetupService(ConfigurationService configuration) {
        this.configuration = configuration;
    }

    public StartResult saveProtectedRegion(KothFamily family, Position first, Position second) {
        if (!first.world().equals(second.world())) {
            return StartResult.failure("Both protected-region corners must be in the same world.");
        }
        if (first.x() == second.x() || first.y() == second.y() || first.z() == second.z()) {
            return StartResult.failure("Protected-region corners must form a non-zero cuboid.");
        }
        configuration.saveProtectedRegion(family, first, second);
        return StartResult.success("Saved the " + family.key() + " protected region.");
    }
}
