package com.enthusia.koth.application.event;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.family.KothFamilyHandler;
import com.enthusia.koth.application.family.TickResult;
import com.enthusia.koth.application.lock.LockService;
import com.enthusia.koth.application.ports.AnnouncementPort;
import com.enthusia.koth.application.ports.ArenaRepository;
import com.enthusia.koth.application.ports.DisplayPort;
import com.enthusia.koth.application.reward.RewardService;
import com.enthusia.koth.domain.EventState;
import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.EventKind;
import com.enthusia.koth.domain.PrivateTestAccess;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.event.ActiveEvent;
import com.enthusia.koth.domain.event.EventRequest;
import com.enthusia.koth.domain.event.QueuedEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.logging.Logger;

public final class ActiveEventService {
    private final ArenaRepository arenas;
    private final ConfigurationService configuration;
    private final LockService locks;
    private final RewardService rewards;
    private final AnnouncementPort announcements;
    private final DisplayPort display;
    private final Logger logger;
    private final Map<com.enthusia.koth.domain.KothFamily, KothFamilyHandler> handlers;
    private final Queue<QueuedEvent> queue = new ArrayDeque<>();
    private BukkitTask task;
    private ActiveEvent activeEvent;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Application services are shared by dependency injection.")
    public ActiveEventService(ArenaRepository arenas, ConfigurationService configuration, LockService locks, RewardService rewards,
                              AnnouncementPort announcements, DisplayPort display, Collection<KothFamilyHandler> handlers, Logger logger) {
        this.arenas = arenas;
        this.configuration = configuration;
        this.locks = locks;
        this.rewards = rewards;
        this.announcements = announcements;
        this.display = display;
        this.logger = logger;
        this.handlers = new EnumMap<>(com.enthusia.koth.domain.KothFamily.class);
        handlers.forEach(handler -> this.handlers.put(handler.family(), handler));
    }

    public synchronized void attachTask(BukkitTask task) {
        this.task = task;
    }

    public synchronized StartResult requestStart(EventRequest request) {
        if (!configuration.settings().isFamilyEnabled(request.family())) {
            return StartResult.failure(request.family().key() + " is not enabled.");
        }
        if (!locks.allows(request.source())) {
            return StartResult.failure("KOTH starts are locked.");
        }
        if ((request.source() == StartSource.MANUAL || request.source() == StartSource.PRIVATE_TEST) && hasActiveOrStarting()) {
            return StartResult.failure("A KOTH is already running. Manual KOTHs do not queue.");
        }
        if (hasActiveOrStarting()) {
            if (request.queueIfBusy() && request.source() == StartSource.SCHEDULED) {
                queue.add(new QueuedEvent(request, Instant.now()));
                return StartResult.success("Scheduled KOTH queued.");
            }
            return StartResult.failure("A KOTH is already running.");
        }
        return startNow(request);
    }

    public synchronized Optional<ActiveEvent> activeEvent() {
        return Optional.ofNullable(activeEvent);
    }

    public synchronized int queuedCount() {
        return queue.size();
    }

    public synchronized StartResult joinPrivateTest(org.bukkit.entity.Player player) {
        if (activeEvent == null || !activeEvent.isPrivateTest()) {
            return StartResult.failure("There is no private KOTH lobby to join.");
        }
        if (activeEvent.request().privateTestAccess() != PrivateTestAccess.PERMISSION_JOIN) {
            return StartResult.failure("This private KOTH is owner-only.");
        }
        if (activeEvent.state() != EventState.STARTING && activeEvent.state() != EventState.ACTIVE) {
            return StartResult.failure("This private KOTH is no longer accepting participants.");
        }
        if (!activeEvent.join(player.getUniqueId())) {
            return StartResult.failure("You are already in this private KOTH.");
        }
        return StartResult.success("Joined the private KOTH.");
    }

    public synchronized StartResult leavePrivateTest(org.bukkit.entity.Player player) {
        if (activeEvent == null || !activeEvent.isPrivateTest() || !activeEvent.isParticipant(player.getUniqueId())) {
            return StartResult.failure("You are not in a private KOTH.");
        }
        if (activeEvent.isOwner(player.getUniqueId())) {
            return StartResult.failure("The private KOTH owner must cancel the event instead.");
        }
        activeEvent.leave(player.getUniqueId());
        return StartResult.success("Left the private KOTH.");
    }

    public synchronized StartResult cancelPrivateTest(org.bukkit.entity.Player player) {
        if (activeEvent == null || !activeEvent.isPrivateTest() || !activeEvent.isOwner(player.getUniqueId())) {
            return StartResult.failure("You do not own an active private KOTH.");
        }
        cancelActive("owner cancelled private test");
        return StartResult.success("Private KOTH cancelled.");
    }

    public synchronized void cancelActive(String reason) {
        if (activeEvent == null) {
            return;
        }
        activeEvent.state(EventState.CANCELLED);
        announcements.announceEnded(activeEvent, Optional.of("Cancelled: " + reason));
        activeEvent = null;
        display.clear();
        startNextQueued();
    }

    public synchronized void tick() {
        if (activeEvent == null) {
            return;
        }
        Instant now = Instant.now();
        if (activeEvent.state() == EventState.STARTING && !now.isBefore(activeEvent.startsAt())) {
            activeEvent.state(EventState.ACTIVE);
            announcements.announceStarted(activeEvent);
        }
        if (activeEvent.state() != EventState.ACTIVE) {
            return;
        }
        KothFamilyHandler handler = handlers.get(activeEvent.request().family());
        TickResult result = handler.tick(activeEvent, now);
        display.tick(activeEvent);
        if (result.finished()) {
            finishActive(handler);
        }
    }

    public synchronized void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (activeEvent != null) {
            activeEvent.state(EventState.CANCELLED);
            activeEvent = null;
        }
        queue.clear();
        display.clear();
    }

    private StartResult startNow(EventRequest request) {
        var arena = arenas.findDefault(request.family());
        if (arena.isEmpty()) {
            return StartResult.failure("No arena configured for " + request.family().key() + ".");
        }
        KothFamilyHandler handler = handlers.get(request.family());
        if (handler == null) {
            return StartResult.failure("No handler registered for " + request.family().key() + ".");
        }
        ArenaDefinition eventArena = applyPrivateTestTiming(request, arena.get());
        Instant startsAt = request.startAt();
        Instant endsAt = startsAt.plusSeconds(eventArena.durationSeconds());
        activeEvent = new ActiveEvent(UUID.randomUUID(), request, eventArena, startsAt, endsAt);
        handler.start(activeEvent);
        boolean startsImmediately = !startsAt.isAfter(Instant.now());
        if (!startsImmediately) {
            announcements.announceStarting(activeEvent);
        } else {
            activeEvent.state(EventState.ACTIVE);
            announcements.announceStarted(activeEvent);
        }
        return StartResult.success(startsImmediately ? "KOTH started." : "KOTH starts at " + startsAt + ".");
    }

    private boolean hasActiveOrStarting() {
        return activeEvent != null && activeEvent.state() != EventState.COMPLETED && activeEvent.state() != EventState.CANCELLED;
    }

    private void finishActive(KothFamilyHandler handler) {
        activeEvent.state(EventState.ENDING);
        Optional<String> winnerKey = handler.winnerDisplay(activeEvent);
        Optional<String> winnerDisplay = rewards.rewardWinner(activeEvent, winnerKey);
        activeEvent.state(EventState.COMPLETED);
        announcements.announceEnded(activeEvent, winnerDisplay);
        if (activeEvent.isPrivateTest()) {
            logger.info("Private KOTH " + activeEvent.id() + " ended.");
        } else {
            logger.info("KOTH " + activeEvent.id() + " ended. Winner=" + winnerDisplay.orElse("none"));
        }
        activeEvent = null;
        display.clear();
        startNextQueued();
    }

    private void startNextQueued() {
        QueuedEvent next = queue.poll();
        if (next != null) {
            EventRequest request = new EventRequest(next.request().requestId(), next.request().family(), next.request().teamMode(),
                    next.request().source(), next.request().requestedBy(), Instant.now(), next.request().rules(), true,
                    EventKind.STANDARD, null, false);
            startNow(request);
        }
    }

    private ArenaDefinition applyPrivateTestTiming(EventRequest request, ArenaDefinition arena) {
        if (!request.isPrivateTest() || !request.quickTiming()) {
            return arena;
        }
        var testing = configuration.settings().privateTesting();
        return new ArenaDefinition(arena.id(), arena.family(), arena.protectedRegion(), arena.zone(),
                Math.toIntExact(testing.quickMatchDuration().toSeconds()),
                arena.family() == com.enthusia.koth.domain.KothFamily.CAPTURE ? testing.quickCaptureSeconds() : arena.captureSeconds(),
                arena.leaveBehavior(), arena.decayPerSecond(), arena.movingSquareSize(), arena.movingSpeedBlocksPerSecond());
    }
}
