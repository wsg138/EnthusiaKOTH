package com.enthusia.koth.application.ports;

import com.enthusia.koth.domain.event.ActiveEvent;

import java.util.Optional;

public interface AnnouncementPort {
    void announceStarting(ActiveEvent event);
    void announceStarted(ActiveEvent event);
    void announceProgress(ActiveEvent event);
    void announceEnded(ActiveEvent event, Optional<String> winner);
}
