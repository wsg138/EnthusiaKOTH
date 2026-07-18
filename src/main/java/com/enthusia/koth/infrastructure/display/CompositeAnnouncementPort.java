package com.enthusia.koth.infrastructure.display;

import com.enthusia.koth.application.ports.AnnouncementPort;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.event.ActiveEvent;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public final class CompositeAnnouncementPort implements AnnouncementPort {
    private final List<AnnouncementPort> delegates;

    public CompositeAnnouncementPort(List<AnnouncementPort> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override public void announceUpcoming(KothFamily family, Instant startsAt) { delegates.forEach(delegate -> delegate.announceUpcoming(family, startsAt)); }
    @Override public void announceStarting(ActiveEvent event) { delegates.forEach(delegate -> delegate.announceStarting(event)); }
    @Override public void announceStarted(ActiveEvent event) { delegates.forEach(delegate -> delegate.announceStarted(event)); }
    @Override public void announceProgress(ActiveEvent event) { delegates.forEach(delegate -> delegate.announceProgress(event)); }
    @Override public void announceEnded(ActiveEvent event, Optional<String> winner) { delegates.forEach(delegate -> delegate.announceEnded(event, winner)); }
}
