package com.enthusia.koth.application.schedule;

import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.application.event.ActiveEventService;
import com.enthusia.koth.application.ports.AnnouncementPort;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.EventKind;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.event.EventRequest;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import java.util.concurrent.ThreadLocalRandom;

public final class ScheduleService {
    private final ConfigurationService config;
    private final ActiveEventService activeEvents;
    private final AnnouncementPort announcements;
    private BukkitTask task;
    private LocalDate plannedDate;
    private List<KothFamily> dailyOrder = List.of(KothFamily.CAPTURE, KothFamily.MOVING, KothFamily.CONQUEST);
    private Instant lastTriggered;
    private Instant lastWarned;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Application services are shared by dependency injection.")
    public ScheduleService(ConfigurationService config, ActiveEventService activeEvents, AnnouncementPort announcements) {
        this.config = config;
        this.activeEvents = activeEvents;
        this.announcements = announcements;
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
            if (i >= dailyOrder.size()) {
                continue;
            }
            ZonedDateTime scheduled = ZonedDateTime.of(LocalDateTime.of(now.toLocalDate(), times.get(i)), config.settings().scheduleZone());
            ZonedDateTime warning = scheduled.minus(config.settings().schedulePreStartWarning());
            if (!config.settings().schedulePreStartWarning().isZero()
                    && !now.isBefore(warning) && now.isBefore(scheduled)
                    && Duration.between(warning, now).toSeconds() <= 30) {
                Instant warningKey = scheduled.toInstant();
                if (!warningKey.equals(lastWarned)) {
                    lastWarned = warningKey;
                    announcements.announceUpcoming(dailyOrder.get(i % dailyOrder.size()), warningKey);
                }
            }
            if (!now.isBefore(scheduled) && Duration.between(scheduled, now).abs().toSeconds() <= 30) {
                Instant key = scheduled.toInstant();
                if (!key.equals(lastTriggered)) {
                    lastTriggered = key;
                    KothFamily family = dailyOrder.get(i % dailyOrder.size());
                    TeamMode mode = family == KothFamily.CONQUEST
                            ? TeamMode.GUILD
                            : randomTeamMode();
                    activeEvents.requestStart(new EventRequest(UUID.randomUUID(), family, mode, StartSource.SCHEDULED, null,
                            Instant.now(), config.settings().defaultRules().get(family), true, EventKind.STANDARD, null, false));
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
        lastWarned = null;
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
        List<KothFamily> families = new ArrayList<>();
        for (KothFamily family : KothFamily.values()) {
            if (config.settings().isFamilyEnabled(family)) {
                families.add(family);
            }
        }
        Collections.shuffle(families);
        dailyOrder = families;
        plannedDate = date;
    }

    private TeamMode randomTeamMode() {
        return ThreadLocalRandom.current().nextBoolean() ? TeamMode.SOLO : TeamMode.GUILD;
    }
}
