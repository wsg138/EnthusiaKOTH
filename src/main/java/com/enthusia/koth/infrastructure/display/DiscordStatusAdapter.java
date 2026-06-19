package com.enthusia.koth.infrastructure.display;

import com.enthusia.koth.application.ports.AnnouncementPort;
import com.enthusia.koth.domain.event.ActiveEvent;

import java.util.Optional;

public final class DiscordStatusAdapter implements AnnouncementPort {
    @Override public void announceStarting(ActiveEvent event) {}
    @Override public void announceStarted(ActiveEvent event) {}
    @Override public void announceProgress(ActiveEvent event) {}
    @Override public void announceEnded(ActiveEvent event, Optional<String> winner) {}
}
