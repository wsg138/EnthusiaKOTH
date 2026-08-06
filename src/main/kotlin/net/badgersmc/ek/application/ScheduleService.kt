package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.persistence.ScheduleStateStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class ScheduledOccurrence(
    val id: String,
    val arenaId: String,
    val instant: Instant,
)

class ScheduleService(
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val kothService: KothService,
    private val arenas: () -> Map<String, KothArena>,
    private val stateStore: ScheduleStateStore,
    private val clock: Clock,
    private val warningSink: (arenaId: String, minutes: Int) -> Unit,
    private val logger: (String) -> Unit,
) {
    companion object {
        internal val MAX_CATCH_UP: Duration = Duration.ofMinutes(10)
        private val CLAIM_RETENTION: Duration = Duration.ofDays(3)

        internal fun parseScheduleTime(value: String): LocalTime? {
            val parts = value.trim().split(':')
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
    }

    private val invalidLogged = mutableSetOf<String>()

    fun tick() = tickAt(clock.instant())

    internal fun tickAt(now: Instant) {
        val cfg = cfgLoader()
        val previous = stateStore.lastEvaluation()
        if (!cfg.schedule.enabled) {
            stateStore.setLastEvaluation(now)
            return
        }

        val lowerBound = now.minus(MAX_CATCH_UP)
        val from = when {
            previous == null -> now.minusSeconds(1)
            previous.isAfter(now) -> now.minusSeconds(1)
            previous.isBefore(lowerBound) -> lowerBound
            else -> previous
        }
        val warningSeconds = cfg.schedule.preStartWarningSeconds.coerceAtLeast(0)
        val occurrences = occurrencesBetween(from, now.plusSeconds(warningSeconds.toLong()), cfg)

        if (warningSeconds > 0) {
            occurrences.forEach { occurrence ->
                val warningAt = occurrence.instant.minusSeconds(warningSeconds.toLong())
                if (warningAt.isAfter(from) && !warningAt.isAfter(now)) {
                    val claim = "warning:${occurrence.id}"
                    if (stateStore.claim(claim, warningAt)) {
                        warningSink(occurrence.arenaId, (warningSeconds + 59) / 60)
                    }
                }
            }
        }

        occurrences.filter { it.instant.isAfter(from) && !it.instant.isAfter(now) }
            .sortedWith(compareBy<ScheduledOccurrence> { it.instant }.thenBy { it.id })
            .forEach { occurrence ->
                val claim = "start:${occurrence.id}"
                if (!stateStore.claim(claim, occurrence.instant)) return@forEach
                val arena = arenas()[occurrence.arenaId]
                if (arena == null) {
                    logger("Scheduled occurrence ${occurrence.id} references missing arena '${occurrence.arenaId}'; removing claim so a corrected reload may retry")
                    stateStore.release(claim)
                    return@forEach
                }
                try {
                    if (!kothService.queueStart(arena, EventKind.SCHEDULED, occurrence.instant)) {
                        stateStore.release(claim)
                        logger("Scheduled occurrence ${occurrence.id} was rejected before it could start or queue")
                    }
                } catch (error: Throwable) {
                    stateStore.release(claim)
                    logger("Scheduled occurrence ${occurrence.id} threw before it could start or queue: ${error.message}")
                }
            }

        stateStore.setLastEvaluation(now)
        stateStore.prune(now.minus(CLAIM_RETENTION))
        kothService.processQueue()
    }

    fun nextScheduledStart(): Instant? = nextOccurrenceAfter(clock.instant())?.instant

    fun nextEventInfo(): Pair<String, String>? {
        val now = clock.instant()
        val next = nextOccurrenceAfter(now) ?: return null
        return next.arenaId to formatDuration(Duration.between(now, next.instant).seconds.coerceAtLeast(0))
    }

    internal fun nextOccurrenceAfter(now: Instant): ScheduledOccurrence? {
        val cfg = cfgLoader()
        if (!cfg.schedule.enabled) return null
        return occurrencesBetween(now, now.plus(Duration.ofDays(2)), cfg)
            .filter { it.instant.isAfter(now) }
            .minWithOrNull(compareBy<ScheduledOccurrence> { it.instant }.thenBy { it.id })
    }

    internal fun occurrencesBetween(from: Instant, to: Instant, cfg: EnthusiaKothConfig = cfgLoader()): List<ScheduledOccurrence> {
        if (!cfg.schedule.enabled || to.isBefore(from)) return emptyList()
        val zone = cfg.schedule.zone
        val startDate = from.atZone(zone).toLocalDate().minusDays(1)
        val endDate = to.atZone(zone).toLocalDate().plusDays(1)
        val result = mutableListOf<ScheduledOccurrence>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            addArenaOccurrences(date, zone, result)
            addLegacyOccurrences(date, zone, cfg, result)
            date = date.plusDays(1)
        }
        val warningSeconds = cfg.schedule.preStartWarningSeconds.coerceAtLeast(0).toLong()
        return result.filter { !it.instant.isBefore(from.minusSeconds(warningSeconds)) && !it.instant.isAfter(to) }
    }

    private fun addArenaOccurrences(
        date: LocalDate,
        zone: ZoneId,
        result: MutableList<ScheduledOccurrence>,
    ) {
        arenas().values.sortedBy { it.id }.forEach { arena ->
            arena.schedule.forEachIndexed { index, configured ->
                val time = parseOrLog("arena:${arena.id}:$index", configured) ?: return@forEachIndexed
                val instant = ZonedDateTime.of(date, time, zone).toInstant()
                result += ScheduledOccurrence(
                    id = "arena:${arena.id}:$index:${date}:$configured:${instant.toEpochMilli()}",
                    arenaId = arena.id,
                    instant = instant,
                )
            }
        }
    }

    private fun addLegacyOccurrences(
        date: LocalDate,
        zone: ZoneId,
        cfg: EnthusiaKothConfig,
        result: MutableList<ScheduledOccurrence>,
    ) {
        val order = dailyOrder(date)
        if (order.isEmpty()) return
        cfg.schedule.times.forEachIndexed { index, configured ->
            val time = parseOrLog("legacy:$index", configured) ?: return@forEachIndexed
            val arena = order[index % order.size]
            val instant = ZonedDateTime.of(date, time, zone).toInstant()
            result += ScheduledOccurrence(
                id = "legacy:$index:${arena.id}:${date}:$configured:${instant.toEpochMilli()}",
                arenaId = arena.id,
                instant = instant,
            )
        }
    }

    private fun dailyOrder(date: LocalDate): List<KothArena> {
        val candidates = arenas().values.filter { it.schedule.isEmpty() }.sortedBy { it.id }
        if (candidates.isEmpty()) return emptyList()
        val shift = Math.floorMod(date.toEpochDay(), candidates.size.toLong()).toInt()
        return candidates.drop(shift) + candidates.take(shift)
    }

    private fun parseOrLog(key: String, value: String): LocalTime? {
        val parsed = parseScheduleTime(value)
        if (parsed == null && invalidLogged.add("$key=$value")) {
            logger("Ignoring invalid KOTH schedule time '$value' at $key; expected HH:mm")
        }
        return parsed
    }

    fun reload() {
        invalidLogged.clear()
    }

    fun reset() = reload()

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
