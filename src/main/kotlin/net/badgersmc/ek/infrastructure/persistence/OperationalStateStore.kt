package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.QueuedEvent
import net.badgersmc.ek.domain.EventKind
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Base64

interface ScheduleStateStore {
    fun lastEvaluation(): Instant?
    fun setLastEvaluation(value: Instant)
    fun claim(key: String, occurrence: Instant): Boolean
    fun release(key: String)
    fun prune(before: Instant)
}

interface EventQueueStore {
    fun load(): List<QueuedEvent>
    fun save(queue: List<QueuedEvent>)
}

class InMemoryScheduleStateStore : ScheduleStateStore {
    private var evaluated: Instant? = null
    private val claims = linkedMapOf<String, Instant>()

    @Synchronized override fun lastEvaluation(): Instant? = evaluated
    @Synchronized override fun setLastEvaluation(value: Instant) { evaluated = value }
    @Synchronized override fun claim(key: String, occurrence: Instant): Boolean =
        if (claims.containsKey(key)) false else { claims[key] = occurrence; true }
    @Synchronized override fun release(key: String) { claims.remove(key) }
    @Synchronized override fun prune(before: Instant) { claims.entries.removeIf { it.value.isBefore(before) } }
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
) : ScheduleStateStore, EventQueueStore {
    private var loaded = false
    private var evaluated: Instant? = null
    private val claims = linkedMapOf<String, Instant>()

    @Synchronized
    private fun ensureScheduleLoaded() {
        if (loaded) return
        loaded = true
        if (!scheduleFile.isFile) return
        runCatching {
            scheduleFile.readLines().forEach { line ->
                when {
                    line.startsWith("last=") -> evaluated = line.removePrefix("last=").toLongOrNull()?.let(Instant::ofEpochMilli)
                    line.startsWith("claim=") -> {
                        val parts = line.removePrefix("claim=").split('|', limit = 2)
                        val instant = parts.getOrNull(0)?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@forEach
                        val key = parts.getOrNull(1)?.decode() ?: return@forEach
                        claims[key] = instant
                    }
                }
            }
        }.onFailure { logger("Failed to read KOTH schedule state; duplicate suppression may be incomplete", it) }
    }

    @Synchronized override fun lastEvaluation(): Instant? { ensureScheduleLoaded(); return evaluated }

    @Synchronized override fun setLastEvaluation(value: Instant) {
        ensureScheduleLoaded()
        evaluated = value
        persistSchedule()
    }

    @Synchronized override fun claim(key: String, occurrence: Instant): Boolean {
        ensureScheduleLoaded()
        if (claims.containsKey(key)) return false
        claims[key] = occurrence
        return runCatching { persistSchedule(); true }.getOrElse {
            claims.remove(key)
            logger("Failed to persist KOTH occurrence claim '$key'", it)
            false
        }
    }

    @Synchronized override fun release(key: String) {
        ensureScheduleLoaded()
        if (claims.remove(key) != null) runCatching(::persistSchedule)
            .onFailure { logger("Failed to release KOTH occurrence claim '$key'", it) }
    }

    @Synchronized override fun prune(before: Instant) {
        ensureScheduleLoaded()
        if (claims.entries.removeIf { it.value.isBefore(before) }) runCatching(::persistSchedule)
            .onFailure { logger("Failed to prune KOTH occurrence state", it) }
    }

    @Synchronized override fun load(): List<QueuedEvent> {
        if (!queueFile.isFile) return emptyList()
        return runCatching {
            queueFile.readLines().mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 6 || parts[0] != "q") return@mapNotNull null
                val arenaId = parts[1].decode()
                val source = runCatching { EventKind.valueOf(parts[2]) }.getOrNull() ?: return@mapNotNull null
                val scheduledAt = parts[3].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
                val attempts = parts[4].toIntOrNull() ?: return@mapNotNull null
                val nextAttemptAt = parts[5].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@mapNotNull null
                QueuedEvent(arenaId, source, scheduledAt, attempts, nextAttemptAt)
            }
        }.getOrElse {
            logger("Failed to read persisted KOTH queue; leaving it empty", it)
            emptyList()
        }
    }

    @Synchronized override fun save(queue: List<QueuedEvent>) {
        val lines = queue.map { event ->
            listOf(
                "q",
                event.arenaId.encode(),
                event.startSource.name,
                event.scheduledAt.toEpochMilli().toString(),
                event.attempts.toString(),
                event.nextAttemptAt.toEpochMilli().toString(),
            ).joinToString("|")
        }
        atomicWrite(queueFile, lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

    private fun persistSchedule() {
        val lines = buildList {
            evaluated?.let { add("last=${it.toEpochMilli()}") }
            claims.forEach { (key, time) -> add("claim=${time.toEpochMilli()}|${key.encode()}") }
        }
        atomicWrite(scheduleFile, lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
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
    private fun String.decode(): String = String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8)
}
