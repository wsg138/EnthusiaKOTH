package com.enthusia.koth.application.family;

import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.event.ActiveEvent;

import java.time.Instant;
import java.util.Optional;

public interface KothFamilyHandler {
    KothFamily family();
    void start(ActiveEvent event);
    TickResult tick(ActiveEvent event, Instant now);
    Optional<String> winnerDisplay(ActiveEvent event);
}
