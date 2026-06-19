package com.enthusia.koth.application.schedule;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.event.EventRequest;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ScheduleService {
    private final ConfigurationService config;
    private final ActiveEventService activeEvents;
    private BukkitTask task;
    private LocalDate plannedDate;
    private List<KothFamily> dailyOrder = List.of(KothFamily.CAPTURE, KothFamily.MOVING, KothFamily.CONQUEST);
    private Instant lastTriggered;

    public ScheduleService(ConfigurationService config, ActiveEventService activeEvents) {
        this.config = config;
        this.activeEvents = activeEvents;
    }

    public void attachTask(BukkitTask task) {
        this.task = task;
    }

    public void tick() {
        if (!config.settings().scheduleEnabled()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(config.settings().scheduleZone());
        ensureOrder(now.toLocalDate());
        List<LocalTime> times = config.settings().scheduleTimes();
        for (int i = 0; i < times.size(); i++) {
            ZonedDateTime scheduled = ZonedDateTime.of(LocalDateTime.of(now.toLocalDate(), times.get(i)), config.settings().scheduleZone());
            if (!now.isBefore(scheduled) && Duration.between(scheduled, now).abs().toSeconds() <= 30) {
                Instant key = scheduled.toInstant();
                if (!key.equals(lastTriggered)) {
                    lastTriggered = key;
                    KothFamily family = dailyOrder.get(i % dailyOrder.size());
                    TeamMode mode = family == KothFamily.CONQUEST ? TeamMode.GUILD : (Math.random() < 0.5 ? TeamMode.SOLO : TeamMode.GUILD);
                    activeEvents.requestStart(new EventRequest(UUID.randomUUID(), family, mode, StartSource.SCHEDULED, null,
                            Instant.now(), config.settings().defaultRules().get(family), true));
                }
            }
        }
    }

    public Instant nextScheduledStart() {
        ZonedDateTime now = ZonedDateTime.now(config.settings().scheduleZone());
        return config.settings().scheduleTimes().stream()
                .map(time -> ZonedDateTime.of(LocalDateTime.of(now.toLocalDate(), time), config.settings().scheduleZone()))
                .map(time -> time.isBefore(now) || time.isEqual(now) ? time.plusDays(1) : time)
                .min(ZonedDateTime::compareTo)
                .orElse(now.plusDays(1))
                .toInstant();
    }

    public void reload() {
        plannedDate = null;
        lastTriggered = null;
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void ensureOrder(LocalDate date) {
        if (date.equals(plannedDate)) {
            return;
        }
        List<KothFamily> families = new ArrayList<>(List.of(KothFamily.CAPTURE, KothFamily.MOVING, KothFamily.CONQUEST));
        Collections.shuffle(families);
        dailyOrder = families;
        plannedDate = date;
    }
}
