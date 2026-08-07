package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.QueuedEvent
import net.badgersmc.ek.application.QueuedEventState
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.TeamMode
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

enum class ScheduleClaimStatus { PENDING, COMMITTED }

data class PendingScheduleClaim(
    val occurrence: Instant,
    val arenaId: String? = null,
    val teamMode: TeamMode? = null,
)

private data class StoredScheduleClaim(
    val occurrence: Instant,
    val status: ScheduleClaimStatus,
    val arenaId: String? = null,
    val teamMode: TeamMode? = null,
)

interface ScheduleStateStore {
    fun lastEvaluation(): Instant?
    fun setLastEvaluation(value: Instant)
    fun claim(
        key: String,
        occurrence: Instant,
        arenaId: String? = null,
        teamMode: TeamMode? = null,
    ): Boolean
    fun claimStatus(key: String): ScheduleClaimStatus?
    fun pendingClaims(prefix: String = ""): Map<String, PendingScheduleClaim>
    fun commit(key: String): Boolean
    fun release(key: String): Boolean
    fun prune(before: Instant, protectedKeys: Set<String> = emptySet())
    fun flush() {}
}

interface EventQueueStore {
    fun load(): List<QueuedEvent>
    fun save(queue: List<QueuedEvent>)
}

class InMemoryScheduleStateStore : ScheduleStateStore {
    private var evaluated: Instant? = null
    private val claims = linkedMapOf<String, StoredScheduleClaim>()

    @Synchronized override fun lastEvaluation(): Instant? = evaluated
    @Synchronized override fun setLastEvaluation(value: Instant) { evaluated = value }
    @Synchronized override fun claim(
        key: String,
        occurrence: Instant,
        arenaId: String?,
        teamMode: TeamMode?,
    ): Boolean = if (claims.containsKey(key)) false else {
        claims[key] = StoredScheduleClaim(occurrence, ScheduleClaimStatus.PENDING, arenaId, teamMode)
        true
    }

    @Synchronized override fun claimStatus(key: String): ScheduleClaimStatus? = claims[key]?.status

    @Synchronized override fun pendingClaims(prefix: String): Map<String, PendingScheduleClaim> = claims
        .filter { (key, claim) -> key.startsWith(prefix) && claim.status == ScheduleClaimStatus.PENDING }
        .mapValues { (_, claim) -> PendingScheduleClaim(claim.occurrence, claim.arenaId, claim.teamMode) }

    @Synchronized override fun commit(key: String): Boolean {
        val claim = claims[key] ?: return false
        if (claim.status == ScheduleClaimStatus.COMMITTED) return true
        claims[key] = claim.copy(status = ScheduleClaimStatus.COMMITTED)
        return true
    }

    @Synchronized override fun release(key: String): Boolean {
        claims.remove(key)
        return true
    }

    @Synchronized override fun prune(before: Instant, protectedKeys: Set<String>) {
        claims.entries.removeIf { (key, claim) ->
            claim.status == ScheduleClaimStatus.COMMITTED &&
                key !in protectedKeys &&
                claim.occurrence.isBefore(before)
        }
    }
}

class InMemoryEventQueueStore(initial: List<QueuedEvent> = emptyList()) : EventQueueStore {
    private var queue = initial.toList()
    @Synchronized override fun load(): List<QueuedEvent> = queue.toList()
    @Synchronized override fun save(queue: List<QueuedEvent>) { this.queue = queue.toList() }
}

class FileOperationalStateStore(
    private val scheduleFile: File,
    private val queueFile: File,
    private val logger: (String, Throwable?) -> Unit,
    private val schedulePersistObserver: () -> Unit = {},
    private val schedulePersistInterceptor: () -> Unit = {},
    private val queuePersistInterceptor: () -> Unit = {},
) : ScheduleStateStore, EventQueueStore {
    companion object {
        internal val EVALUATION_PERSIST_INTERVAL: Duration = Duration.ofMinutes(1)
    }

    private var loaded = false
    private var evaluated: Instant? = null
    private var lastPersistedEvaluation: Instant? = null
    private val claims = linkedMapOf<String, StoredScheduleClaim>()

    @Synchronized
    private fun ensureScheduleLoaded() {
        if (loaded) return
        loaded = true
        if (!scheduleFile.isFile) return
        runCatching {
            scheduleFile.readLines().forEach { line ->
                when {
                    line.startsWith("last=") -> evaluated = line.removePrefix("last=").toLongOrNull()?.let(Instant::ofEpochMilli)
                    line.startsWith("claim=") -> loadClaim(line.removePrefix("claim="))
                }
            }
        }.onFailure { logger("Failed to read KOTH schedule state; duplicate suppression may be incomplete", it) }
        lastPersistedEvaluation = evaluated
    }

    private fun loadClaim(serialized: String) {
        val parts = serialized.split('|')
        val instant = parts.firstOrNull()?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return
        when (parts.size) {
            // Legacy claims predate transactional pending/committed state. They represented
            // occurrences already accepted by the scheduler, so treat them as committed.
            2 -> {
                val key = parts[1].decodeOrNull() ?: return
                claims[key] = StoredScheduleClaim(instant, ScheduleClaimStatus.COMMITTED)
            }
            3 -> {
                // Transactional v1 rows did not yet include immutable occurrence metadata.
                val status = runCatching { ScheduleClaimStatus.valueOf(parts[1]) }.getOrNull() ?: return
                val key = parts[2].decodeOrNull() ?: return
                claims[key] = StoredScheduleClaim(instant, status)
            }
            5 -> {
                val status = runCatching { ScheduleClaimStatus.valueOf(parts[1]) }.getOrNull() ?: return
                val key = parts[2].decodeOrNull() ?: return
                val arenaId = parts[3].takeUnless { it == "-" }?.decodeOrNull()
                val teamMode = parts[4].takeUnless { it == "-" }
                    ?.let { runCatching { TeamMode.valueOf(it) }.getOrNull() }
                claims[key] = StoredScheduleClaim(instant, status, arenaId, teamMode)
            }
        }
    }

    @Synchronized override fun lastEvaluation(): Instant? { ensureScheduleLoaded(); return evaluated }

    @Synchronized override fun setLastEvaluation(value: Instant) {
        ensureScheduleLoaded()
        evaluated = value
        val persisted = lastPersistedEvaluation
        val shouldPersist = persisted == null ||
            value.isBefore(persisted) ||
            Duration.between(persisted, value) >= EVALUATION_PERSIST_INTERVAL
        if (shouldPersist) {
            runCatching(::persistSchedule)
                .onFailure { logger("Failed to persist KOTH schedule evaluation cursor", it) }
        }
    }

    @Synchronized override fun claim(
        key: String,
        occurrence: Instant,
        arenaId: String?,
        teamMode: TeamMode?,
    ): Boolean {
        ensureScheduleLoaded()
        if (claims.containsKey(key)) return false
        claims[key] = StoredScheduleClaim(occurrence, ScheduleClaimStatus.PENDING, arenaId, teamMode)
        return runCatching { persistSchedule(); true }.getOrElse {
            claims.remove(key)
            logger("Failed to persist KOTH occurrence claim '$key'", it)
            false
        }
    }

    @Synchronized override fun claimStatus(key: String): ScheduleClaimStatus? {
        ensureScheduleLoaded()
        return claims[key]?.status
    }

    @Synchronized override fun pendingClaims(prefix: String): Map<String, PendingScheduleClaim> {
        ensureScheduleLoaded()
        return claims
            .filter { (key, claim) -> key.startsWith(prefix) && claim.status == ScheduleClaimStatus.PENDING }
            .mapValues { (_, claim) -> PendingScheduleClaim(claim.occurrence, claim.arenaId, claim.teamMode) }
    }

    @Synchronized override fun commit(key: String): Boolean {
        ensureScheduleLoaded()
        val previous = claims[key] ?: return false
        if (previous.status == ScheduleClaimStatus.COMMITTED) return true
        claims[key] = previous.copy(status = ScheduleClaimStatus.COMMITTED)
        return runCatching { persistSchedule(); true }.getOrElse {
            claims[key] = previous
            logger("Failed to commit KOTH occurrence claim '$key'", it)
            false
        }
    }

    @Synchronized override fun release(key: String): Boolean {
        ensureScheduleLoaded()
        val previous = claims.remove(key) ?: return true
        return runCatching { persistSchedule(); true }.getOrElse {
            claims[key] = previous
            logger("Failed to release KOTH occurrence claim '$key'; the claim remains pending in memory and on disk", it)
            false
        }
    }

    @Synchronized override fun prune(before: Instant, protectedKeys: Set<String>) {
        ensureScheduleLoaded()
        val removed = claims.filter { (key, claim) ->
            claim.status == ScheduleClaimStatus.COMMITTED &&
                key !in protectedKeys &&
                claim.occurrence.isBefore(before)
        }
        if (removed.isEmpty()) return
        removed.keys.forEach(claims::remove)
        runCatching(::persistSchedule).onFailure {
            claims.putAll(removed)
            logger("Failed to prune KOTH occurrence state", it)
        }
    }

    @Synchronized override fun flush() {
        ensureScheduleLoaded()
        if (evaluated != lastPersistedEvaluation) {
            runCatching(::persistSchedule)
                .onFailure { logger("Failed to flush KOTH schedule state", it) }
        }
    }

    @Synchronized override fun load(): List<QueuedEvent> {
        if (!queueFile.isFile) return emptyList()
        return runCatching {
            queueFile.readLines().mapNotNull(::loadQueuedEvent)
        }.getOrElse {
            logger("Failed to read persisted KOTH queue; leaving it empty", it)
            emptyList()
        }
    }

    private fun loadQueuedEvent(line: String): QueuedEvent? {
        val parts = line.split('|')
        if (parts.firstOrNull() != "q") return null
        val arenaId = parts.getOrNull(1)?.decodeOrNull() ?: return null
        val source = parts.getOrNull(2)?.let { runCatching { EventKind.valueOf(it) }.getOrNull() } ?: return null
        val scheduledAt = parts.getOrNull(3)?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null

        return when (parts.size) {
            // v1 queue rows from before event team mode was durable.
            6 -> QueuedEvent(
                arenaId = arenaId,
                startSource = source,
                scheduledAt = scheduledAt,
                teamMode = TeamMode.SOLO,
                attempts = parts[4].toIntOrNull() ?: return null,
                nextAttemptAt = parts[5].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null,
            )
            // v2 queue rows from before activation state/occurrence identity was durable.
            7 -> QueuedEvent(
                arenaId = arenaId,
                startSource = source,
                scheduledAt = scheduledAt,
                teamMode = runCatching { TeamMode.valueOf(parts[4]) }.getOrNull() ?: return null,
                attempts = parts[5].toIntOrNull() ?: return null,
                nextAttemptAt = parts[6].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null,
            )
            10 -> QueuedEvent(
                arenaId = arenaId,
                startSource = source,
                scheduledAt = scheduledAt,
                teamMode = runCatching { TeamMode.valueOf(parts[4]) }.getOrNull() ?: return null,
                state = runCatching { QueuedEventState.valueOf(parts[5]) }.getOrNull() ?: return null,
                attempts = parts[6].toIntOrNull() ?: return null,
                nextAttemptAt = parts[7].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null,
                occurrenceId = parts[8].takeUnless { it == "-" }?.decodeOrNull(),
                activationId = parts[9].takeUnless { it == "-" }?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            )
            else -> null
        }
    }

    @Synchronized override fun save(queue: List<QueuedEvent>) {
        val lines = queue.map { event ->
            listOf(
                "q",
                event.arenaId.encode(),
                event.startSource.name,
                event.scheduledAt.toEpochMilli().toString(),
                event.teamMode.name,
                event.state.name,
                event.attempts.toString(),
                event.nextAttemptAt.toEpochMilli().toString(),
                event.occurrenceId?.encode() ?: "-",
                event.activationId?.toString() ?: "-",
            ).joinToString("|")
        }
        queuePersistInterceptor()
        atomicWrite(queueFile, lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

    private fun persistSchedule() {
        val lines = buildList {
            evaluated?.let { add("last=${it.toEpochMilli()}") }
            claims.forEach { (key, claim) ->
                add(
                    listOf(
                        "claim=${claim.occurrence.toEpochMilli()}",
                        claim.status.name,
                        key.encode(),
                        claim.arenaId?.encode() ?: "-",
                        claim.teamMode?.name ?: "-",
                    ).joinToString("|"),
                )
            }
        }
        schedulePersistInterceptor()
        atomicWrite(scheduleFile, lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
        lastPersistedEvaluation = evaluated
        schedulePersistObserver()
    }

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        Files.writeString(temporary.toPath(), content, StandardCharsets.UTF_8)
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun String.encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(StandardCharsets.UTF_8))
    private fun String.decodeOrNull(): String? = runCatching {
        String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8)
    }.getOrNull()
}
