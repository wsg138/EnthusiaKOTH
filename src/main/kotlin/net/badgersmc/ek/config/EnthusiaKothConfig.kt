package net.badgersmc.ek.config

import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.restriction.RuleSet
import java.time.ZoneId

data class EnthusiaKothConfig(
    val timezone: ZoneId = ZoneId.of("America/New_York"),
    val manualStart: ManualStartConfig = ManualStartConfig(),
    val schedule: ScheduleConfig = ScheduleConfig(),
    val flares: FlareConfig = FlareConfig(),
    val progressBar: ProgressBarConfig = ProgressBarConfig(),
    val reminders: ReminderConfig = ReminderConfig(),
    val messages: MessageConfig = MessageConfig(),
    val arenas: Map<String, ArenaConfig> = emptyMap(),
    val rewards: Map<String, RewardConfig> = emptyMap(),
    val discord: DiscordConfig = DiscordConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val rules: FamilyRulesConfig = FamilyRulesConfig(),
    val privateTesting: PrivateTestingConfig = PrivateTestingConfig(),
    val locks: LockConfig = LockConfig(),
)

data class ManualStartConfig(
    val enabled: Boolean = true,
    val basicCost: Double = 0.0,
    val advancedCost: Double = 0.0,
    val delaySeconds: Int = 0,
)

data class ScheduleConfig(
    val enabled: Boolean = false,
    val zone: ZoneId = ZoneId.of("America/New_York"),
    val preStartWarningSeconds: Int = 300,
    val times: List<String> = listOf("00:00", "08:00", "16:00"),
)

data class FlareConfig(
    val enabled: Boolean = true,
    val item: FlareItemConfig = FlareItemConfig(),
    val messages: FlareMessagesConfig = FlareMessagesConfig(),
)

data class FlareItemConfig(
    val material: String = "REDSTONE_TORCH",
    val name: String = "&#c{KOTH} Koth Flare",
    val lore: List<String> = listOf("&7Right-Click to start a &c{KOTH} &7koth!"),
)

data class FlareMessagesConfig(
    val giveFlare: String = "&aGave &e{PLAYER} {AMOUNT} &akoth flares.",
    val receivedFlare: String = "&aYou received a &e{KOTH} &akoth flare!",
    val notInRegion: String = "&cYou must be on the &e{KOTH} &ccapture point to use the flare!",
    val alreadyActive: String = "&cThe &e{KOTH} &ckoth is already active.",
    val startedWithFlare: String = "&aStarted the &e{KOTH} &akoth with your flare.",
    val startedBroadcast: String = "&e{PLAYER} &astarted the &e{KOTH} &akoth with a flare! Go to &e{LOCATION}&a!",
)

data class ProgressBarConfig(
    val enabled: Boolean = true,
    val length: Int = 10,
    val character: String = "|",
    val format: String = "&cKOTH Progress: &8[{PROGRESS_BAR}&8]",
)

data class ReminderConfig(
    val enabled: Boolean = true,
    val intervalSeconds: Int = 300,
    val format: String = "&aReminder that the {KOTH} koth is still active! {CAPPER}&8({TIME_LEFT})&a is currently capturing.",
)

data class MessageConfig(
    val enterMessage: String = "&a{ENTERED} entered the {KOTH_NAME} koth! Cap in {CAP_TIME}!",
    val leaveMessage: String = "&a{LEFT} left the {KOTH_NAME} koth! {TIME_LEFT} left!",
    val cappingMessage: String = "&a{CAPPING} is capturing the {KOTH_NAME} koth! {TIME_LEFT} left!",
    val captureMessage: String = "&a{CAPTURED} captured the {KOTH_NAME} koth!",
    val beginMessage: String = "&a{KOTH_NAME} has begun! Location: {LOCATION}",
    val forcefullyEnded: String = "&cThe koth {KOTH} has been forcefully ended!",
    val kothTopHeader: String = "&7&m-----&r &c&lKoth Top &7(&c{PAGE}&8/&c{PAGE_MAX}&7) &m-----",
    val kothTopFormat: String = "&e{INDEX}. &a{USER} &e{WINS} &awins",
    val kothStatsFormat: String = "&e{PLAYER} &ahas &e{WINS} &akoth wins.",
    val kothScheduleHeader: String = "&7&m-----&r &a&lKoth Schedule &7&m-----\n &cTime Now: {TIME_NOW}\n &cTimeZone: {TIME_ZONE}",
)

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

data class ProtectedRegionConfig(
    val corner1: PositionConfig = PositionConfig(-32.0, -64.0, -32.0),
    val corner2: PositionConfig = PositionConfig(32.0, 320.0, 32.0),
)

data class RewardConfig(
    val soloVaultMoney: Double = 0.0,
    val guildVaultMoney: Double = 0.0,
)

data class DiscordConfig(
    val enabled: Boolean = false,
    val webhookUrl: String = "",
    val preStartPingMinutes: Int = 10,
    val liveUpdateSeconds: Int = 60,
)

data class DisplayConfig(val zoneBorder: Boolean = true)

data class FamilyRulesConfig(val rules: Map<String, RuleSet> = emptyMap())

data class PrivateTestingConfig(
    val lobbySeconds: Int = 0,
    val quickMatchDurationSeconds: Int = 120,
    val quickCaptureSeconds: Int = 15,
    val showObjectiveParticles: Boolean = true,
)

data class LockConfig(val state: LockState = LockState.UNLOCKED)
