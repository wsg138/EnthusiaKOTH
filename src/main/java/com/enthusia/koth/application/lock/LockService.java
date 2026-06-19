package com.enthusia.koth.application.lock;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.domain.LockState;
import com.enthusia.koth.domain.StartSource;

public final class LockService {
    private final ConfigurationService configurationService;
    private volatile LockState state;

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
        if (state == LockState.UNLOCKED) {
            return true;
        }
        if (state == LockState.MANUAL_LOCKED) {
            return source == StartSource.SCHEDULED;
        }
        return false;
    }

    public void reload() {
        this.state = configurationService.settings().lockState();
    }
}
