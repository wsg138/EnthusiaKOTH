package com.enthusia.koth.application.lock;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.domain.LockState;
import com.enthusia.koth.domain.StartSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class LockService {
    private final ConfigurationService configurationService;
    private volatile LockState state;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Configuration service is shared by dependency injection.")
    public LockService(ConfigurationService configurationService) {
        this.configurationService = configurationService;
        this.state = configurationService.settings().lockState();
    }

    public LockState state() {
        return state;
    }

    public void setState(LockState state) {
        this.state = state;
        configurationService.setLockState(state);
        configurationService.saveLockState();
    }

    public boolean allows(StartSource source) {
        return switch (state) {
            case UNLOCKED -> true;
            case MANUAL_LOCKED -> source == StartSource.SCHEDULED || source == StartSource.PRIVATE_TEST;
            case ALL_LOCKED -> false;
        };
    }

    public void reload() {
        this.state = configurationService.settings().lockState();
    }
}
