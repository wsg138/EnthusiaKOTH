package com.enthusia.koth.application.lock;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.config.PluginSettings;
import com.enthusia.koth.application.config.PrivateTestingSettings;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.LockState;
import com.enthusia.koth.domain.StartSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LockServiceTest {
    @Test
    void manualLockAllowsScheduledAndPrivateTestsOnly() {
        LockService service = new LockService(new TestConfiguration(LockState.MANUAL_LOCKED));

        assertTrue(service.allows(StartSource.SCHEDULED));
        assertTrue(service.allows(StartSource.PRIVATE_TEST));
        assertFalse(service.allows(StartSource.MANUAL));
        assertFalse(service.allows(StartSource.ADMIN));
    }

    private static final class TestConfiguration implements ConfigurationService {
        private final PluginSettings settings;

        private TestConfiguration(LockState lockState) {
            settings = new PluginSettings(2, ZoneId.of("America/New_York"), Duration.ofMinutes(45), Duration.ofMinutes(1), 96,
                    lockState, 0, 0, true, List.of(), Map.<KothFamily, com.enthusia.koth.domain.ArenaDefinition>of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), false, "", new PrivateTestingSettings(Duration.ofSeconds(30), Duration.ofMinutes(2), 15, true));
        }

        @Override public PluginSettings settings() { return settings; }
        @Override public void setLockState(LockState state) { }
        @Override public void saveLockState() { }
        @Override public void reload() { }
    }
}
