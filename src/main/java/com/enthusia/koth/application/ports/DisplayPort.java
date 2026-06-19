package com.enthusia.koth.application.ports;

import com.enthusia.koth.domain.event.ActiveEvent;

public interface DisplayPort {
    void tick(ActiveEvent event);
    void clear();
}
