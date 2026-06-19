package com.enthusia.koth.domain.event;

import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.EventState;
import com.enthusia.koth.domain.team.TeamId;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ActiveEvent {
    private final UUID id;
    private final EventRequest request;
    private final ArenaDefinition arena;
    private final Instant startsAt;
    private final Instant endsAt;
    private final Map<TeamId, Double> scores = new ConcurrentHashMap<>();
    private volatile EventState state;
    private volatile TeamId currentController;

    public ActiveEvent(UUID id, EventRequest request, ArenaDefinition arena, Instant startsAt, Instant endsAt) {
        this.id = id;
        this.request = request;
        this.arena = arena;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.state = EventState.STARTING;
    }

    public UUID id() {
        return id;
    }

    public EventRequest request() {
        return request;
    }

    public ArenaDefinition arena() {
        return arena;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public EventState state() {
        return state;
    }

    public void state(EventState state) {
        this.state = state;
    }

    public Optional<TeamId> currentController() {
        return Optional.ofNullable(currentController);
    }

    public void currentController(TeamId currentController) {
        this.currentController = currentController;
    }

    public Map<TeamId, Double> scores() {
        return scores;
    }

    public void addScore(TeamId teamId, double amount) {
        scores.merge(teamId, amount, Double::sum);
    }

    public void setScore(TeamId teamId, double amount) {
        scores.put(teamId, amount);
    }
}
