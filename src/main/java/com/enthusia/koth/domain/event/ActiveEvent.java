package com.enthusia.koth.domain.event;

import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.EventState;
import com.enthusia.koth.domain.Position;
import com.enthusia.koth.domain.team.TeamId;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ActiveEvent {
    private final UUID id;
    private final EventRequest request;
    private final ArenaDefinition arena;
    private final Instant startsAt;
    private final Instant endsAt;
    private final Map<TeamId, Double> scores = new ConcurrentHashMap<>();
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private volatile EventState state;
    private volatile TeamId currentController;
    private volatile Position objectivePosition;

    public ActiveEvent(UUID id, EventRequest request, ArenaDefinition arena, Instant startsAt, Instant endsAt) {
        this.id = id;
        this.request = request;
        this.arena = arena;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.state = EventState.STARTING;
        this.objectivePosition = arena.zone().center();
        if (request.isPrivateTest() && request.requestedBy() != null) {
            this.participants.add(request.requestedBy());
        }
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

    public boolean isPrivateTest() {
        return request.isPrivateTest();
    }

    public boolean isParticipant(UUID playerId) {
        return !isPrivateTest() || participants.contains(playerId);
    }

    public Set<UUID> participants() {
        return Set.copyOf(participants);
    }

    public boolean join(UUID playerId) {
        if (!isPrivateTest() || (state != EventState.STARTING && state != EventState.ACTIVE)) {
            return false;
        }
        return participants.add(playerId);
    }

    public boolean leave(UUID playerId) {
        return isPrivateTest() && participants.remove(playerId);
    }

    public boolean isOwner(UUID playerId) {
        return playerId.equals(request.requestedBy());
    }

    public Position objectivePosition() {
        return objectivePosition;
    }

    public void objectivePosition(Position objectivePosition) {
        this.objectivePosition = objectivePosition;
    }

    public Map<TeamId, Double> scores() {
        return Map.copyOf(scores);
    }

    public void clearScores() {
        scores.clear();
    }

    public void addScore(TeamId teamId, double amount) {
        scores.merge(teamId, amount, Double::sum);
    }

    public void setScore(TeamId teamId, double amount) {
        scores.put(teamId, amount);
    }
}
