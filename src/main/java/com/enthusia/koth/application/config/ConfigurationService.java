package com.enthusia.koth.application.config;

import com.enthusia.koth.application.ReloadableService;

public interface ConfigurationService extends ReloadableService {
    PluginSettings settings();
    void setLockState(com.enthusia.koth.domain.LockState state);
    void saveLockState();
}
