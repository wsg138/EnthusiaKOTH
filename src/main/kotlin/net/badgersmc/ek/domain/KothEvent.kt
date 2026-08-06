package net.badgersmc.ek.domain

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class KothEvent(
    val id: UUID,
    val arena: KothArena,
    val startsAt: Instant,
    val endsAt: Instant,
    @Volatile var state: EventState = EventState.STARTING,
    val owner: UUID? = null,
    val isPrivateTest: Boolean = false,
    val lobbySeconds: Int = 0,
) {
    val scores: MutableMap<TeamId, Double> = ConcurrentHashMap()
    val participants: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    @Volatile var currentController: TeamId? = null
    @Volatile var previousControllerTime: Double = 0.0
    @Volatile var movingPoint: Triple<Double, Double, Double>? = null
    @Volatile var movingStartEpoch: Long = 0
    @Volatile var leaveAnnouncedFor: TeamId? = null

    fun isParticipant(playerId: UUID): Boolean = !isPrivateTest || playerId in participants || playerId == owner
    fun join(playerId: UUID): Boolean = participants.add(playerId)
    fun leave(playerId: UUID): Boolean = participants.remove(playerId)
    fun isOwner(playerId: UUID): Boolean = owner == playerId
    fun clearScores() { scores.clear() }
    fun addScore(team: TeamId, amount: Double) { scores.merge(team, amount, Double::plus) }
    fun setScore(team: TeamId, score: Double) { scores[team] = score }
}

data class KothArena(
    val id: String,
    val family: String,
    val zone: CaptureZone,
    val protectedRegion: CaptureZone? = null,
    val durationSeconds: Int,
    val captureSeconds: Int,
    val leaveBehavior: CaptureLeaveBehavior = CaptureLeaveBehavior.RESET,
    val decayPerSecond: Double = 1.0,
    val movingSquareSize: Double = 20.0,
    val movingSpeedBlocksPerSecond: Double = 1.0,
    val ignoreFactions: Boolean = false,
    val contestWhenMultipleCappers: Boolean = true,
    val flaresMustBePlacedOnCap: Boolean = true,
    val schedule: List<String> = emptyList(),
    val rewards: List<String> = emptyList(),
    val chancedRewards: Map<String, Double> = emptyMap(),
    val captureSpeedBonuses: Map<Int, Double> = emptyMap(),
)

data class TeamId(
    val mode: TeamMode,
    val id: UUID,
) {
    fun storageKey(): String = "${mode.name.lowercase()}:$id"
}
