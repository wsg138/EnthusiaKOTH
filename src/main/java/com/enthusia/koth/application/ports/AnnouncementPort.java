package com.enthusia.koth.application.ports;

import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.KothFamily;

import java.time.Instant;
import java.util.Optional;

public interface AnnouncementPort {
    void announceUpcoming(KothFamily family, Instant startsAt);
    void announceStarting(ActiveEvent event);
    void announceStarted(ActiveEvent event);
    void announceProgress(ActiveEvent event);
    void announceEnded(ActiveEvent event, Optional<String> winner);
}
