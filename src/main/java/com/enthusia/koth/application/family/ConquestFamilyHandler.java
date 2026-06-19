package com.enthusia.koth.application.family;

import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.event.ActiveEvent;

import java.time.Instant;
import java.util.Optional;

public final class ConquestFamilyHandler implements KothFamilyHandler {
    @Override
    public KothFamily family() {
        return KothFamily.CONQUEST;
    }

    @Override
    public void start(ActiveEvent event) {
        event.scores().clear();
    }

    @Override
    public TickResult tick(ActiveEvent event, Instant now) {
        if (!now.isBefore(event.endsAt())) {
            return TickResult.finished("conquest-not-implemented");
        }
        return TickResult.running();
    }

    @Override
    public Optional<String> winnerDisplay(ActiveEvent event) {
        return Optional.empty();
    }
}
