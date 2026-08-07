package net.badgersmc.ek.config

import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.restriction.RuleSet
import java.time.ZoneId

data class EnthusiaKothConfig(
    val configVersion: Int = 6,
    val timezone: ZoneId = ZoneId.of("America/New_York"),
    val manualStart: ManualStartConfig = ManualStartConfig(),
    val schedule: ScheduleConfig = ScheduleConfig(),
    val flares: FlareConfig = FlareConfig(),
    val progressBar: ProgressBarConfig = ProgressBarConfig(),
    val reminders: ReminderConfig = ReminderConfig(),
    val arenas: Map<String, ArenaConfig> = emptyMap(),
    val rewards: Map<String, RewardConfig> = emptyMap(),
    val discord: DiscordConfig = DiscordConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val rules: FamilyRulesConfig = FamilyRulesConfig(),
    val privateTesting: PrivateTestingConfig = PrivateTestingConfig(),
    val locks: LockConfig = LockConfig(),
)

data class ManualStartConfig(val enabled: Boolean = true, val basicCost: Double = 0.0, val advancedCost: Double = 0.0, val delaySeconds: Int = 0)
data class ScheduleConfig(val enabled: Boolean = false, val zone: ZoneId = ZoneId.of("America/New_York"), val preStartWarningSeconds: Int = 300, val times: List<String> = listOf("00:00", "08:00", "16:00"))
data class FlareConfig(val enabled: Boolean = true, val item: FlareItemConfig = FlareItemConfig())
data class FlareItemConfig(val material: String = "REDSTONE_TORCH", val name: String = "&c{KOTH} Koth Flare", val lore: List<String> = listOf("&7Right-Click to start a &c{KOTH} &7koth!"))
data class ProgressBarConfig(val enabled: Boolean = true, val length: Int = 10, val character: String = "|")
data class ReminderConfig(val enabled: Boolean = true, val intervalSeconds: Int = 300)

data class ArenaConfig(
    val enabled: Boolean = true,
    val family: String = "capture",
    val world: String = "world",
    val center: PositionConfig = PositionConfig(),
    val protectedRegion: ProtectedRegionConfig = ProtectedRegionConfig(),
    val radius: Double = 5.0,
    val durationSeconds: Int = 900,
    val captureSeconds: Int = 120,
    val leaveBehavior: String = "RESET",
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

data class PositionConfig(val x: Double = 0.0, val y: Double = 80.0, val z: Double = 0.0)
data class ProtectedRegionConfig(val corner1: PositionConfig = PositionConfig(-32.0, -64.0, -32.0), val corner2: PositionConfig = PositionConfig(32.0, 320.0, 32.0))
data class RewardConfig(val soloVaultMoney: Double = 0.0, val guildVaultMoney: Double = 0.0)
data class DiscordConfig(val enabled: Boolean = false, val webhookUrl: String = "", val preStartPingMinutes: Int = 10, val liveUpdateSeconds: Int = 60)
data class DisplayConfig(val zoneBorder: Boolean = true)
data class FamilyRulesConfig(val rules: Map<String, RuleSet> = emptyMap())
data class PrivateTestingConfig(val lobbySeconds: Int = 0, val quickMatchDurationSeconds: Int = 120, val quickCaptureSeconds: Int = 15, val showObjectiveParticles: Boolean = true)
data class LockConfig(val state: LockState = LockState.UNLOCKED)
