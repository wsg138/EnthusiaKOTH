package com.enthusia.koth.application.setup;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.config.PluginSettings;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.LockState;
import com.enthusia.koth.domain.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArenaSetupServiceTest {
    @Test
    void onlyPersistsAValidSameWorldCuboid() {
        TestConfiguration configuration = new TestConfiguration();
        ArenaSetupService service = new ArenaSetupService(configuration);

        assertFalse(service.saveProtectedRegion(KothFamily.CAPTURE,
                new Position("world", 0, 0, 0), new Position("world_nether", 10, 10, 10)).success());
        assertFalse(configuration.saved);

        assertTrue(service.saveProtectedRegion(KothFamily.CAPTURE,
                new Position("world", 0, 0, 0), new Position("world", 10, 10, 10)).success());
        assertTrue(configuration.saved);
    }

    private static final class TestConfiguration implements ConfigurationService {
        private boolean saved;

        @Override public PluginSettings settings() { return null; }
        @Override public void setLockState(LockState state) { }
        @Override public void saveLockState() { }
        @Override public void saveProtectedRegion(KothFamily family, Position first, Position second) { saved = true; }
        @Override public void reload() { }
    }
}
