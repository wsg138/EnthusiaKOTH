package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.toComponent
import org.bukkit.Bukkit
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Scheduled auto-start for KOTH events.
 * Supports both:
 * 1. Legacy EnthusiaKOTH daily rotation (shuffled each day)
 * 2. FactionsKore-style per-arena HH:mm schedules
 */
class ScheduleService(
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val kothService: KothService,
    private val arenas: () -> Map<String, KothArena>,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) {
    private var plannedDate: LocalDate? = null
    private var dailyOrder: List<KothArena> = emptyList()
    private val lastTriggered = mutableMapOf<String, String>()
    private val lastWarned = mutableMapOf<String, String>()

    /**
     * Resets all scheduling state (called on reload) so changed schedules take
     * effect immediately — stale "last triggered"/"last warned" markers would
     * otherwise suppress the next run of an edited schedule.
     */
    fun reset() {
        plannedDate = null
        dailyOrder = emptyList()
        lastTriggered.clear()
        lastWarned.clear()
    }

    fun tick() {
        val cfg = cfgLoader()
        if (!cfg.schedule.enabled) return

        val now = ZonedDateTime.now(cfg.schedule.zone)
        ensureOrder(now.toLocalDate())

        // Check per-arena schedules (FactionsKore style)
        val allArenas = arenas().values
        for (arena in allArenas) {
            for (timeStr in arena.schedule) {
                val parts = timeStr.split(":")
                if (parts.size != 2) continue
                val hour = parts[0].toIntOrNull() ?: continue
                val minute = parts[1].toIntOrNull() ?: continue
                val scheduled = ZonedDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute), cfg.schedule.zone)
                val warning = scheduled.minusSeconds(cfg.schedule.preStartWarningSeconds.toLong())

                // Warning
                if (cfg.schedule.preStartWarningSeconds > 0
                    && !now.isBefore(warning) && now.isBefore(scheduled)
                    && Duration.between(warning, now).toSeconds() <= 30
                ) {
                    val key = "${arena.id}@${scheduled}"
                    if (key != lastWarned[key]) {
                        lastWarned[key] = key
                        // Announce upcoming
                        Bukkit.getOnlinePlayers().forEach { p ->
                            p.sendMessage(lang.msg("koth.warning_minutes", "koth" to arena.id, "minutes" to (cfg.schedule.preStartWarningSeconds / 60).toString()))
                        }
                    }
                }

                // Trigger
                if (!now.isBefore(scheduled) && Duration.between(scheduled, now).abs().toSeconds() <= 30) {
                    val key = "${arena.id}@${scheduled}"
                    if (key != lastTriggered[key]) {
                        lastTriggered[key] = key
                        kothService.queueStart(arena)
                    }
                }
            }
        }

        // Legacy daily rotation check
        val times = cfg.schedule.times
        for ((i, timeStr) in times.withIndex()) {
            if (i >= dailyOrder.size) continue
            val parts = timeStr.split(":")
            if (parts.size != 2) continue
            val hour = parts[0].toIntOrNull() ?: continue
            val minute = parts[1].toIntOrNull() ?: continue
            val scheduled = ZonedDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute), cfg.schedule.zone)
            val warning = scheduled.minusSeconds(cfg.schedule.preStartWarningSeconds.toLong())

            if (cfg.schedule.preStartWarningSeconds > 0
                && !now.isBefore(warning) && now.isBefore(scheduled)
                && Duration.between(warning, now).toSeconds() <= 30
            ) {
                val key = "daily_$i@${scheduled}"
                if (key != lastWarned[key]) {
                    lastWarned[key] = key
                }
            }

            if (!now.isBefore(scheduled) && Duration.between(scheduled, now).abs().toSeconds() <= 30) {
                val key = "daily_$i@${scheduled}"
                if (key != lastTriggered[key] && i < dailyOrder.size) {
                    lastTriggered[key] = key
                    val arena = dailyOrder[i]
                    kothService.queueStart(arena)
                }
            }
        }
    }

    private fun ensureOrder(date: LocalDate) {
        if (date == plannedDate) return
        val unscheduledArenas = arenas().values.filter { it.schedule.isEmpty() } // Legacy rotation: arenas without per-arena schedules
        dailyOrder = unscheduledArenas.shuffled()
        plannedDate = date
    }

    fun nextScheduledStart(): Instant? {
        val cfg = cfgLoader()
        val now = ZonedDateTime.now(cfg.schedule.zone)

        // Find next per-arena schedule
        var earliest: ZonedDateTime? = null
        for (arena in arenas().values) {
            for (timeStr in arena.schedule) {
                val parts = timeStr.split(":")
                if (parts.size != 2) continue
                val hour = parts[0].toIntOrNull() ?: continue
                val minute = parts[1].toIntOrNull() ?: continue
                var time = ZonedDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute), cfg.schedule.zone)
                if (!time.isAfter(now)) time = time.plusDays(1)
                if (earliest == null || time.isBefore(earliest)) earliest = time
            }
        }

        // Also check legacy schedule
        for (timeStr in cfg.schedule.times) {
            val parts = timeStr.split(":")
            if (parts.size != 2) continue
            val hour = parts[0].toIntOrNull() ?: continue
            val minute = parts[1].toIntOrNull() ?: continue
            var time = ZonedDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute), cfg.schedule.zone)
            if (!time.isAfter(now)) time = time.plusDays(1)
            if (earliest == null || time.isBefore(earliest)) earliest = time
        }

        return earliest?.toInstant()
    }

    fun reload() {
        plannedDate = null
        lastTriggered.clear()
        lastWarned.clear()
    }
}
