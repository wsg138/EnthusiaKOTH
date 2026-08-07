package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.QueuedEvent
import net.badgersmc.ek.application.QueuedEventState
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.TeamMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID

class OperationalStateStoreTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `evaluation cursor is buffered instead of rewriting schedule state every second`() {
        var writes = 0
        val store = FileOperationalStateStore(
            scheduleFile = temp.resolve("schedule.dat").toFile(),
            queueFile = temp.resolve("queue.dat").toFile(),
            logger = { _, _ -> },
            schedulePersistObserver = { writes++ },
        )
        val start = Instant.parse("2026-08-06T12:00:00Z")

        repeat(30) { second ->
            store.setLastEvaluation(start.plusSeconds(second.toLong()))
        }
        assertEquals(1, writes)

        store.setLastEvaluation(start.plusSeconds(60))
        assertEquals(2, writes)

        store.setLastEvaluation(start.plusSeconds(61))
        assertEquals(2, writes)
        store.flush()
        assertEquals(3, writes)
    }

    @Test
    fun `failed claim release keeps memory and disk pending for restart recovery`() {
        val scheduleFile = temp.resolve("schedule-release.dat").toFile()
        val queueFile = temp.resolve("queue-release.dat").toFile()
        var writes = 0
        val occurrence = Instant.parse("2026-08-06T12:00:00Z")
        val store = FileOperationalStateStore(
            scheduleFile = scheduleFile,
            queueFile = queueFile,
            logger = { _, _ -> },
            schedulePersistInterceptor = {
                writes++
                if (writes == 2) error("injected release persistence failure")
            },
        )

        assertTrue(store.claim("start:occurrence", occurrence, "capture", TeamMode.GUILD))
        assertFalse(store.release("start:occurrence"))
        assertEquals(ScheduleClaimStatus.PENDING, store.claimStatus("start:occurrence"))
        assertEquals(
            PendingScheduleClaim(occurrence, "capture", TeamMode.GUILD),
            store.pendingClaims("start:").getValue("start:occurrence"),
        )

        val restarted = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> })
        assertEquals(ScheduleClaimStatus.PENDING, restarted.claimStatus("start:occurrence"))
        assertEquals(
            PendingScheduleClaim(occurrence, "capture", TeamMode.GUILD),
            restarted.pendingClaims("start:").getValue("start:occurrence"),
        )
    }

    @Test
    fun `claim commit is transactional when durable write fails`() {
        val scheduleFile = temp.resolve("schedule-commit.dat").toFile()
        val queueFile = temp.resolve("queue-commit.dat").toFile()
        var writes = 0
        val occurrence = Instant.parse("2026-08-06T12:00:00Z")
        val store = FileOperationalStateStore(
            scheduleFile = scheduleFile,
            queueFile = queueFile,
            logger = { _, _ -> },
            schedulePersistInterceptor = {
                writes++
                if (writes == 2) error("injected commit persistence failure")
            },
        )

        assertTrue(store.claim("start:occurrence", occurrence))
        assertFalse(store.commit("start:occurrence"))
        assertEquals(ScheduleClaimStatus.PENDING, store.claimStatus("start:occurrence"))

        val restarted = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> })
        assertEquals(ScheduleClaimStatus.PENDING, restarted.claimStatus("start:occurrence"))
    }

    @Test
    fun `legacy schedule claims load as committed duplicate suppressors`() {
        val scheduleFile = temp.resolve("schedule-legacy.dat").toFile()
        val queueFile = temp.resolve("queue-legacy.dat").toFile()
        val occurrence = Instant.parse("2026-08-06T12:00:00Z")
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("start:legacy".toByteArray(StandardCharsets.UTF_8))
        scheduleFile.writeText("claim=${occurrence.toEpochMilli()}|$encoded\n")

        val store = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> })

        assertEquals(ScheduleClaimStatus.COMMITTED, store.claimStatus("start:legacy"))
    }

    @Test
    fun `legacy transactional pending claims without metadata remain readable`() {
        val scheduleFile = temp.resolve("schedule-legacy-pending.dat").toFile()
        val queueFile = temp.resolve("queue-legacy-pending.dat").toFile()
        val occurrence = Instant.parse("2026-08-06T12:00:00Z")
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("start:legacy-pending".toByteArray(StandardCharsets.UTF_8))
        scheduleFile.writeText("claim=${occurrence.toEpochMilli()}|PENDING|$encoded\n")

        val store = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> })

        assertEquals(
            PendingScheduleClaim(occurrence),
            store.pendingClaims("start:").getValue("start:legacy-pending"),
        )
    }

    @Test
    fun `queue persists activation state identity and keeps old rows readable`() {
        val scheduleFile = temp.resolve("schedule-mode.dat").toFile()
        val queueFile = temp.resolve("queue-mode.dat").toFile()
        val scheduledAt = Instant.parse("2026-08-06T12:00:00Z")
        val activationId = UUID.randomUUID()
        val store = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> })
        store.save(
            listOf(
                QueuedEvent(
                    arenaId = "capture",
                    startSource = EventKind.SCHEDULED,
                    scheduledAt = scheduledAt,
                    teamMode = TeamMode.GUILD,
                    attempts = 65,
                    nextAttemptAt = scheduledAt.plusSeconds(10),
                    state = QueuedEventState.ACTIVATING,
                    occurrenceId = "arena:capture:0",
                    activationId = activationId,
                ),
            ),
        )

        val reloaded = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> }).load().single()
        assertEquals(TeamMode.GUILD, reloaded.teamMode)
        assertEquals(65, reloaded.attempts)
        assertEquals(QueuedEventState.ACTIVATING, reloaded.state)
        assertEquals("arena:capture:0", reloaded.occurrenceId)
        assertEquals(activationId, reloaded.activationId)

        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("capture".toByteArray(StandardCharsets.UTF_8))
        queueFile.writeText(
            "q|$encoded|SCHEDULED|${scheduledAt.toEpochMilli()}|61|${scheduledAt.plusSeconds(10).toEpochMilli()}\n",
        )
        val legacyV1 = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> }).load().single()
        assertEquals(TeamMode.SOLO, legacyV1.teamMode)
        assertEquals(QueuedEventState.READY, legacyV1.state)
        assertEquals(61, legacyV1.attempts)

        queueFile.writeText(
            "q|$encoded|SCHEDULED|${scheduledAt.toEpochMilli()}|GUILD|62|${scheduledAt.plusSeconds(20).toEpochMilli()}\n",
        )
        val legacyV2 = FileOperationalStateStore(scheduleFile, queueFile, { _, _ -> }).load().single()
        assertEquals(TeamMode.GUILD, legacyV2.teamMode)
        assertEquals(QueuedEventState.READY, legacyV2.state)
        assertEquals(62, legacyV2.attempts)
    }
}
