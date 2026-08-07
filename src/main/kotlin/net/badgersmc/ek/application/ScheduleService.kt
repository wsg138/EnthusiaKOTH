package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.persistence.ScheduleClaimStatus
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
    val teamMode: TeamMode = TeamMode.SOLO,
)

class ScheduleService(
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val kothService: KothService,
    private val arenas: () -> Map<String, KothArena>,
    private val stateStore: ScheduleStateStore,
    private val clock: Clock,
    private val warningSink: (arenaId: String, minutes: Int) -> Unit,
    private val logger: (String) -> Unit,
    private val discordWarningMinutes: () -> Int = { 0 },
    private val discordWarningSink: (arenaId: String, minutes: Int) -> Unit = { _, _ -> },
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
        val discordMinutes = discordWarningMinutes().coerceAtLeast(0)
        val discordWarningSeconds = discordMinutes.toLong() * 60L
        val warningHorizon = maxOf(warningSeconds.toLong(), discordWarningSeconds)
        val occurrences = occurrencesBetween(from, now.plusSeconds(warningHorizon), cfg)

        if (warningSeconds > 0) {
            occurrences.forEach { occurrence ->
                val warningAt = occurrence.instant.minusSeconds(warningSeconds.toLong())
                if (warningAt.isAfter(from) && !warningAt.isAfter(now)) {
                    val claim = "warning:${occurrence.id}"
                    if (stateStore.claim(claim, warningAt)) {
                        warningSink(occurrence.arenaId, (warningSeconds + 59) / 60)
                        if (!stateStore.commit(claim)) {
                            logger("KOTH warning claim '$claim' was delivered but could not be marked committed")
                        }
                    }
                }
            }
        }

        if (discordWarningSeconds > 0) {
            occurrences.forEach { occurrence ->
                val warningAt = occurrence.instant.minusSeconds(discordWarningSeconds)
                if (warningAt.isAfter(from) && !warningAt.isAfter(now)) {
                    val claim = "discord-warning:${occurrence.id}:$discordMinutes"
                    if (stateStore.claim(claim, warningAt)) {
                        discordWarningSink(occurrence.arenaId, discordMinutes)
                        if (!stateStore.commit(claim)) {
                            logger("KOTH Discord warning claim '$claim' was delivered but could not be marked committed")
                        }
                    }
                }
            }
        }

        val due = occurrences
            .filter { it.instant.isAfter(from) && !it.instant.isAfter(now) }
            .plus(recoverablePendingOccurrences(cfg))
            .distinctBy { it.id }
            .sortedWith(compareBy<ScheduledOccurrence> { it.instant }.thenBy { it.id })

        var retryCursor: Instant? = null
        due.forEach { occurrence ->
            val claim = "start:${occurrence.id}"
            val claimStatus = stateStore.claimStatus(claim)
            val recoveringPending = claimStatus == ScheduleClaimStatus.PENDING
            when (claimStatus) {
                ScheduleClaimStatus.COMMITTED -> return@forEach
                ScheduleClaimStatus.PENDING -> Unit
                null -> if (!stateStore.claim(claim, occurrence.instant, occurrence.arenaId, occurrence.teamMode)) return@forEach
            }

            val arena = arenas()[occurrence.arenaId]
            if (arena == null) {
                logger("Scheduled occurrence ${occurrence.id} references missing arena '${occurrence.arenaId}'; removing its pending claim")
                if (stateStore.release(claim)) {
                    retryCursor = earlierRetryCursor(retryCursor, occurrence.instant)
                } else {
                    logger("Scheduled occurrence ${occurrence.id} could not release its pending claim; restart recovery will keep it recoverable")
                }
                return@forEach
            }

            val queued = try {
                kothService.queueStart(
                    arena,
                    EventKind.SCHEDULED,
                    occurrence.instant,
                    occurrence.teamMode,
                    occurrence.id,
                )
            } catch (error: Throwable) {
                logger("Scheduled occurrence ${occurrence.id} threw before its durable queue write completed: ${error.message}")
                false
            }

            if (queued) {
                if (!stateStore.commit(claim)) {
                    logger(
                        "Scheduled occurrence ${occurrence.id} is durably queued but its claim could not be committed; " +
                            "queue activation will wait while pending-claim recovery reconciles it",
                    )
                }
            } else if (recoveringPending) {
                logger(
                    "Recovered scheduled occurrence ${occurrence.id} was not durably queued; " +
                        "its pending durable claim is retained for retry until handoff succeeds or is explicitly cancelled",
                )
            } else if (stateStore.release(claim)) {
                retryCursor = earlierRetryCursor(retryCursor, occurrence.instant)
                logger("Scheduled occurrence ${occurrence.id} was not durably queued; its claim was released for retry")
            } else {
                logger(
                    "Scheduled occurrence ${occurrence.id} was not durably queued and its claim release also failed; " +
                        "the pending durable claim will be recovered after restart without duplicating the occurrence",
                )
            }
        }

        stateStore.setLastEvaluation(retryCursor ?: now)
        val protectedStartClaims = kothService.queuedOccurrenceIds()
            .mapTo(mutableSetOf()) { occurrenceId -> "start:$occurrenceId" }
        stateStore.prune(now.minus(CLAIM_RETENTION), protectedStartClaims)
        kothService.processQueue()
    }

    private fun recoverablePendingOccurrences(cfg: EnthusiaKothConfig): List<ScheduledOccurrence> =
        stateStore.pendingClaims("start:").mapNotNull { (claim, pending) ->
            val occurrenceId = claim.removePrefix("start:")
            val persistedArenaId = pending.arenaId
            val persistedTeamMode = pending.teamMode
            if (persistedArenaId != null && persistedTeamMode != null) {
                ScheduledOccurrence(
                    id = occurrenceId,
                    arenaId = persistedArenaId,
                    instant = pending.occurrence,
                    teamMode = persistedTeamMode,
                )
            } else {
                // Backward compatibility for short-lived transactional rows written before
                // immutable recovery metadata was added. New rows never depend on live config.
                occurrencesBetween(pending.occurrence.minusNanos(1), pending.occurrence, cfg)
                    .firstOrNull { it.id == occurrenceId }
                    .also { occurrence ->
                        if (occurrence == null && invalidLogged.add("pending:$claim")) {
                            logger(
                                "Legacy pending scheduled occurrence '$occurrenceId' cannot be reconstructed from the current schedule; " +
                                    "retaining its claim for explicit reconciliation",
                            )
                        }
                    }
            }
        }

    private fun earlierRetryCursor(current: Instant?, occurrence: Instant): Instant {
        val candidate = occurrence.minusNanos(1)
        return if (current == null || candidate.isBefore(current)) candidate else current
    }

    fun nextScheduledStart(): Instant? = nextOccurrenceAfter(clock.instant())?.instant

    fun nextEventInfo(): Pair<String, String>? {
        val now = clock.instant()
        val next = nextOccurrenceAfter(now) ?: return null
        return next.arenaId to formatDuration(Duration.between(now, next.instant).seconds.coerceAtLeast(0))
    }

    /** The exact occurrences the scheduler will resolve for a calendar date. */
    fun occurrencesForDate(date: LocalDate): List<ScheduledOccurrence> {
        val cfg = cfgLoader()
        if (!cfg.schedule.enabled) return emptyList()
        val result = mutableListOf<ScheduledOccurrence>()
        addArenaOccurrences(date, cfg.schedule.zone, result)
        addLegacyOccurrences(date, cfg.schedule.zone, cfg, result)
        return result.sortedWith(compareBy<ScheduledOccurrence> { it.instant }.thenBy { it.id })
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
        val warningSeconds = maxOf(
            cfg.schedule.preStartWarningSeconds.coerceAtLeast(0).toLong(),
            discordWarningMinutes().coerceAtLeast(0).toLong() * 60L,
        )
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
                    teamMode = scheduledTeamMode(arena, date, index, configured),
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
                teamMode = scheduledTeamMode(arena, date, index, configured),
            )
        }
    }

    private fun scheduledTeamMode(arena: KothArena, date: LocalDate, index: Int, configured: String): TeamMode {
        if (arena.ignoreFactions) return TeamMode.SOLO
        if (arena.family.equals("conquest", ignoreCase = true)) return TeamMode.GUILD
        val stableKey = "${date.toEpochDay()}:${arena.id}:$index:$configured"
        return if (Math.floorMod(stableKey.hashCode(), 2) == 0) TeamMode.SOLO else TeamMode.GUILD
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

    fun flush() = stateStore.flush()

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
