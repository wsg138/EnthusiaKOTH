package com.enthusia.koth.domain.event;

import java.time.Instant;

public record QueuedEvent(EventRequest request, Instant queuedAt) {
}
