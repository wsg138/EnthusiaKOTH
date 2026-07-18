package com.enthusia.koth.application.config;

import com.enthusia.koth.application.ReloadableService;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.Position;

public interface ConfigurationService extends ReloadableService {
    PluginSettings settings();
    void setLockState(com.enthusia.koth.domain.LockState state);
    void saveLockState();
    void saveProtectedRegion(KothFamily family, Position first, Position second);
}
