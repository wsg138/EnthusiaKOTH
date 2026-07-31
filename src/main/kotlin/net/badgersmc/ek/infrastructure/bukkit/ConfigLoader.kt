package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.config.ArenaConfig
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.CaptureLeaveBehavior
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.restriction.MaceRule
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.time.ZoneId

class ConfigLoader(private val plugin: JavaPlugin) {

    fun load(): EnthusiaKothConfig {
        val config = plugin.config
        return EnthusiaKothConfig(
            timezone = runCatching { ZoneId.of(config.getString("general.timezone", "America/New_York")) }
                .getOrElse {
                    plugin.logger.warning("EnthusiaKOTH: invalid general.timezone '${config.getString("general.timezone")}', falling back to America/New_York")
                    ZoneId.of("America/New_York")
                },
            manualStart = ManualStartConfigLoader.load(config),
            schedule = ScheduleConfigLoader.load(config),
            flares = FlareConfigLoader.load(config),
            progressBar = ProgressBarConfigLoader.load(config),
            reminders = ReminderConfigLoader.load(config),
            messages = MessageConfigLoader.load(config),
            arenas = ArenaConfigLoader.load(config),
            rewards = RewardConfigLoader.load(config),
            discord = DiscordConfigLoader.load(config),
            display = DisplayConfigLoader.load(config),
            rules = RulesConfigLoader.load(config),
            privateTesting = PrivateTestingConfigLoader.load(config),
            locks = LockConfigLoader.load(config),
        )
    }

    fun loadArenas(): Map<String, KothArena> {
        val cfg = load()
        return cfg.arenas.filter { it.value.enabled }.mapNotNull { (id, ac) ->
            val world = Bukkit.getWorld(ac.world) ?: run {
                Bukkit.getLogger().warning("EnthusiaKOTH: World '${ac.world}' not found for arena '$id'")
                return@mapNotNull null
            }
            // Capture/moving zone is derived from center + radius (NOT the
            // protected-region cuboid, which is the much larger protection
            // boundary). Using protected-region corners made players able to
            // capture from anywhere in a 64×384×64 box.
            val isMoving = ac.family.equals("moving", ignoreCase = true)
            // MOVING: the zone bounds the roaming path (square), capture radius
            // is applied per-point. CAPTURE/CONQUEST: zone = center ± radius.
            val half = if (isMoving) ac.movingSquareSize / 2.0 else ac.radius
            val c1 = Location(world, ac.center.x - half, ac.center.y - ac.radius, ac.center.z - half)
            val c2 = Location(world, ac.center.x + half, ac.center.y + ac.radius, ac.center.z + half)
            val zone = CaptureZone(id = id, worldName = ac.world, corner1 = c1, corner2 = c2, radius = ac.radius)
            id to KothArena(
                id = id,
                family = ac.family,
                zone = zone,
                durationSeconds = ac.durationSeconds,
                captureSeconds = ac.captureSeconds,
                leaveBehavior = runCatching { CaptureLeaveBehavior.valueOf(ac.leaveBehavior.uppercase()) }
                    .getOrElse {
                        plugin.logger.warning("EnthusiaKOTH: invalid leave-behavior '${ac.leaveBehavior}' for arena '$id', falling back to RESET")
                        CaptureLeaveBehavior.RESET
                    },
                decayPerSecond = ac.decayPerSecond,
                movingSquareSize = ac.movingSquareSize,
                movingSpeedBlocksPerSecond = ac.movingSpeedBlocksPerSecond,
                ignoreFactions = ac.ignoreFactions,
                contestWhenMultipleCappers = ac.contestWhenMultipleCappers,
                flaresMustBePlacedOnCap = ac.flaresMustBePlacedOnCap,
                schedule = ac.schedule,
                rewards = ac.rewards,
                chancedRewards = ac.chancedRewards,
                captureSpeedBonuses = ac.captureSpeedBonuses,
            )
        }.toMap()
    }

    fun reload() { plugin.reloadConfig() }
}

private fun cs(config: ConfigurationSection, path: String): ConfigurationSection? = config.getConfigurationSection(path)
private fun cbi(config: ConfigurationSection, path: String, def: Boolean): Boolean = if (config.contains(path)) config.getBoolean(path) else def
private fun cdi(config: ConfigurationSection, path: String, def: Int): Int = if (config.contains(path)) config.getInt(path) else def
private fun cdd(config: ConfigurationSection, path: String, def: Double): Double = if (config.contains(path)) config.getDouble(path) else def
private fun cds(config: ConfigurationSection, path: String, def: String): String = config.getString(path) ?: def
private fun cl(config: ConfigurationSection, path: String): List<String> = config.getStringList(path)

private fun loadPos(c: ConfigurationSection, path: String, defX: Double = 0.0, defY: Double = 80.0, defZ: Double = 0.0) = cs(c, path)?.let { sec ->
    net.badgersmc.ek.config.PositionConfig(
        x = cdd(sec, "x", defX),
        y = cdd(sec, "y", defY),
        z = cdd(sec, "z", defZ),
    )
} ?: net.badgersmc.ek.config.PositionConfig(defX, defY, defZ)

private object ManualStartConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.ManualStartConfig(
        enabled = true,
        basicCost = cdd(c, "manual-start.basic-cost", 0.0),
        advancedCost = cdd(c, "manual-start.advanced-cost", 0.0),
    )
}

private object ScheduleConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.ScheduleConfig(
        enabled = cbi(c, "schedule.enabled", false),
        zone = ZoneId.of(cds(c, "general.timezone", "America/New_York")),
        preStartWarningSeconds = cdi(c, "schedule.pre-start-warning-seconds", 300),
        times = cl(c, "schedule.times").ifEmpty { listOf("00:00", "08:00", "16:00") },
    )
}

private object FlareConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.FlareConfig(
        enabled = cbi(c, "flares.enabled", true),
        item = net.badgersmc.ek.config.FlareItemConfig(
            material = cds(c, "flares.item.material", "REDSTONE_TORCH"),
            name = cds(c, "flares.item.name", "{KOTH} Koth Flare"),
            lore = cl(c, "flares.item.lore").ifEmpty { listOf("Right-Click to start a {KOTH} koth!") },
        ),
    )
}

private object ProgressBarConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.ProgressBarConfig(
        enabled = cbi(c, "progress-bar.enabled", true),
        length = cdi(c, "progress-bar.length", 10),
        character = cds(c, "progress-bar.character", "|"),
        format = cds(c, "progress-bar.format", "KOTH Progress: [{PROGRESS_BAR}]"),
    )
}

private object ReminderConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.ReminderConfig(
        enabled = cbi(c, "reminders.enabled", true),
        intervalSeconds = cdi(c, "reminders.interval-seconds", 300),
        format = cds(c, "reminders.format", "Reminder that the {KOTH} koth is still active! {CAPPER}({TIME_LEFT}) is currently capturing."),
    )
}

private object MessageConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.MessageConfig(
        enterMessage = cds(c, "messages.enter", "{ENTERED} entered the {KOTH_NAME} koth! Cap in {CAP_TIME}!"),
        leaveMessage = cds(c, "messages.leave", "{LEFT} left the {KOTH_NAME} koth! {TIME_LEFT} left!"),
        cappingMessage = cds(c, "messages.capping", "{CAPPING} is capturing the {KOTH_NAME} koth! {TIME_LEFT} left!"),
        captureMessage = cds(c, "messages.capture", "{CAPTURED} captured the {KOTH_NAME} koth!"),
        beginMessage = cds(c, "messages.begin", "{KOTH_NAME} has begun! Location: {LOCATION}"),
        forcefullyEnded = cds(c, "messages.forcefully-ended", "The koth {KOTH} has been forcefully ended!"),
        kothTopHeader = cds(c, "messages.koth-top-header", "KOTH Leaderboard (Page {PAGE}/{PAGE_MAX})"),
        kothTopFormat = cds(c, "messages.koth-top-format", "{INDEX}. {USER} - {WINS} wins"),
        kothStatsFormat = cds(c, "messages.koth-stats-format", "{PLAYER} has {WINS} koth wins."),
        kothScheduleHeader = cds(c, "messages.koth-schedule-header", "KOTH Schedule - Time Now: {TIME_NOW} ({TIME_ZONE})"),
    )
}

private object ArenaConfigLoader {
    fun load(c: FileConfiguration): Map<String, ArenaConfig> {
        val section = cs(c, "arenas") ?: return emptyMap()
        return section.getKeys(false).associate { id ->
            val base = section.getConfigurationSection(id) ?: return@associate id to ArenaConfig()
            id to ArenaConfig(
                enabled = cbi(base, "enabled", true),
                family = cds(base, "family", "capture"),
                world = cds(base, "world", "world"),
                center = loadPos(base, "center"),
                protectedRegion = net.badgersmc.ek.config.ProtectedRegionConfig(
                    corner1 = loadPos(base, "protected-region.corner-1", -32.0, -64.0, -32.0),
                    corner2 = loadPos(base, "protected-region.corner-2", 32.0, 320.0, 32.0),
                ),
                radius = cdd(base, "radius", 5.0),
                durationSeconds = cdi(base, "duration-seconds", 900),
                captureSeconds = cdi(base, "capture-seconds", 120),
                leaveBehavior = cds(base, "leave-behavior", "RESET"),
                decayPerSecond = cdd(base, "decay-per-second", 1.0),
                movingSquareSize = cdd(base, "square-size", 20.0),
                movingSpeedBlocksPerSecond = cdd(base, "speed-blocks-per-second", 1.0),
                ignoreFactions = cbi(base, "ignore-factions", false),
                contestWhenMultipleCappers = cbi(base, "contest-when-multiple-cappers", true),
                flaresMustBePlacedOnCap = cbi(base, "flares-must-be-placed-on-cap", true),
                schedule = cl(base, "schedule"),
                rewards = cl(base, "rewards"),
                chancedRewards = loadChanced(base, "chanced-rewards"),
                captureSpeedBonuses = loadIntDoubleMap(base, "capture-speed-bonuses"),
            )
        }
    }

    private fun loadChanced(c: ConfigurationSection, path: String): Map<String, Double> {
        val sec = cs(c, path) ?: return emptyMap()
        // YAML schema: percentage -> command ("20.0": "bank 50")
        // Model: command -> chance percentage (Map<String, Double>)
        return buildMap {
            for (key in sec.getKeys(false)) {
                val command = sec.getString(key) ?: continue
                val chance = key.toDoubleOrNull() ?: continue
                put(command, chance)
            }
        }
    }

    private fun loadIntDoubleMap(c: ConfigurationSection, path: String): Map<Int, Double> {
        val sec = cs(c, path) ?: return emptyMap()
        return buildMap {
            for (key in sec.getKeys(false)) {
                val count = key.toIntOrNull() ?: continue
                put(count, cdd(sec, key, 1.0))
            }
        }
    }
}

private object RewardConfigLoader {
    fun load(c: FileConfiguration): Map<String, net.badgersmc.ek.config.RewardConfig> {
        val section = cs(c, "rewards") ?: return emptyMap()
        return section.getKeys(false).associate { family ->
            val base = cs(c, "rewards.$family")
            family to net.badgersmc.ek.config.RewardConfig(
                soloVaultMoney = if (base != null) cdd(base, "solo-vault-money", 0.0) else 0.0,
                guildVaultMoney = if (base != null) cdd(base, "guild-vault-money", 0.0) else 0.0,
            )
        }
    }
}

private object DiscordConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.DiscordConfig(
        enabled = cbi(c, "discord.enabled", false),
        webhookUrl = cds(c, "discord.webhook-url", ""),
        preStartPingMinutes = cdi(c, "discord.pre-start-ping-minutes", 10),
        liveUpdateSeconds = cdi(c, "discord.live-update-seconds", 60),
    )
}

private object PrivateTestingConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.PrivateTestingConfig(
        lobbySeconds = cdi(c, "private-testing.lobby-seconds", 0),
        quickMatchDurationSeconds = cdi(c, "private-testing.quick-match-duration-seconds", 120),
        quickCaptureSeconds = cdi(c, "private-testing.quick-capture-seconds", 15),
        showObjectiveParticles = cbi(c, "private-testing.show-objective-particles", true),
    )
}

private object LockConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.LockConfig(
        state = try { LockState.valueOf(cds(c, "locks.state", "UNLOCKED")) } catch (_: IllegalArgumentException) { LockState.UNLOCKED },
    )
}

private object DisplayConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.DisplayConfig(
        zoneBorder = cbi(c, "display.zone-border", true),
    )
}

private object RulesConfigLoader {
    fun load(c: FileConfiguration) = net.badgersmc.ek.config.FamilyRulesConfig(
        rules = mapOf(
            "capture" to loadRuleSet(c, "rules.defaults.capture"),
            "moving" to loadRuleSet(c, "rules.defaults.moving"),
            "conquest" to loadRuleSet(c, "rules.defaults.conquest"),
        ),
    )

    private fun loadRuleSet(c: FileConfiguration, path: String): net.badgersmc.ek.infrastructure.restriction.RuleSet {
        val sec = cs(c, path) ?: return net.badgersmc.ek.infrastructure.restriction.RuleSet.PERMISSIVE
        return net.badgersmc.ek.infrastructure.restriction.RuleSet(
            elytraAllowed = cbi(sec, "elytra", true),
            maceRule = try { MaceRule.valueOf(cds(sec, "mace", "FULLY_ALLOWED").uppercase()) }
                catch (_: IllegalArgumentException) { MaceRule.FULLY_ALLOWED },
            spearAllowed = cbi(sec, "spear", true),
            enderPearlAllowed = cbi(sec, "ender-pearl", true),
            windChargeAllowed = cbi(sec, "wind-charge", true),
            maceCooldownSeconds = cdi(sec, "mace-cooldown-seconds", 0),
            spearCooldownSeconds = cdi(sec, "spear-cooldown-seconds", 0),
            enderPearlCooldownSeconds = cdi(sec, "ender-pearl-cooldown-seconds", 0),
            windChargeCooldownSeconds = cdi(sec, "wind-charge-cooldown-seconds", 0),
        )
    }
}
