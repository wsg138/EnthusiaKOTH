package com.enthusia.koth.application.protection;

import com.enthusia.koth.application.config.ConfigurationService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bukkit.Location;

/** Answers whether a location is permanently protected by an enabled KOTH arena. */
public final class KothRegionProtectionService {
    private final ConfigurationService configuration;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Configuration service is shared by bootstrap.")
    public KothRegionProtectionService(ConfigurationService configuration) {
        this.configuration = configuration;
    }

    public boolean isProtected(Location location) {
        return configuration.settings().arenas().entrySet().stream()
                .filter(entry -> configuration.settings().isFamilyEnabled(entry.getKey()))
                .anyMatch(entry -> entry.getValue().protectedRegion().contains(location));
    }
}
