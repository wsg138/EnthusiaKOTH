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

internal val DEFAULT_KOTH_ZONE: ZoneId = ZoneId.of("America/New_York")

internal fun parseZoneId(value: String?, warning: (String) -> Unit): ZoneId {
    val configured = value?.trim().orEmpty().ifBlank { DEFAULT_KOTH_ZONE.id }
    return runCatching { ZoneId.of(configured) }.getOrElse {
        warning("EnthusiaKOTH: invalid general.timezone '$configured'; using ${DEFAULT_KOTH_ZONE.id}")
        DEFAULT_KOTH_ZONE
    }
}

class ConfigLoader(private val plugin: JavaPlugin) {
    fun load(): EnthusiaKothConfig {
        val config = plugin.config
        val zone = parseZoneId(config.getString("general.timezone"), plugin.logger::warning)
        val configVersion = config.getInt("config-version", 0)
        if (configVersion != 6) {
            plugin.logger.warning("EnthusiaKOTH: config-version is $configVersion; current version is 6. Review config.yml before production use.")
        }
        return EnthusiaKothConfig(
            configVersion = configVersion,
            timezone = zone,
            manualStart = ManualStartConfigLoader.load(config),
            schedule = ScheduleConfigLoader.load(config, zone),
            flares = FlareConfigLoader.load(config),
            progressBar = ProgressBarConfigLoader.load(config),
            reminders = ReminderConfigLoader.load(config),
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
        return cfg.arenas.filterValues { it.enabled }.mapNotNull { (id, arenaConfig) ->
            val world = Bukkit.getWorld(arenaConfig.world) ?: run {
                plugin.logger.warning("EnthusiaKOTH: world '${arenaConfig.world}' not found for arena '$id'")
                return@mapNotNull null
            }
            val movingHalf = if (arenaConfig.family.equals("moving", true)) arenaConfig.movingSquareSize / 2.0 else arenaConfig.radius
            val captureZone = CaptureZone(
                id = id,
                worldName = arenaConfig.world,
                corner1 = Location(world, arenaConfig.center.x - movingHalf, arenaConfig.center.y - arenaConfig.radius, arenaConfig.center.z - movingHalf),
                corner2 = Location(world, arenaConfig.center.x + movingHalf, arenaConfig.center.y + arenaConfig.radius, arenaConfig.center.z + movingHalf),
                radius = arenaConfig.radius,
            )
            val protectedRegion = CaptureZone(
                id = "${id}_protected",
                worldName = arenaConfig.world,
                corner1 = Location(world, arenaConfig.protectedRegion.corner1.x, arenaConfig.protectedRegion.corner1.y, arenaConfig.protectedRegion.corner1.z),
                corner2 = Location(world, arenaConfig.protectedRegion.corner2.x, arenaConfig.protectedRegion.corner2.y, arenaConfig.protectedRegion.corner2.z),
            )
            id to KothArena(
                id = id,
                family = arenaConfig.family,
                zone = captureZone,
                protectedRegion = protectedRegion,
                durationSeconds = arenaConfig.durationSeconds.coerceAtLeast(1),
                captureSeconds = arenaConfig.captureSeconds.coerceAtLeast(1),
                leaveBehavior = runCatching { CaptureLeaveBehavior.valueOf(arenaConfig.leaveBehavior.uppercase()) }
                    .getOrElse {
                        plugin.logger.warning("EnthusiaKOTH: invalid leave-behavior '${arenaConfig.leaveBehavior}' for arena '$id'; using RESET")
                        CaptureLeaveBehavior.RESET
                    },
                decayPerSecond = arenaConfig.decayPerSecond.coerceAtLeast(0.0),
                movingSquareSize = arenaConfig.movingSquareSize.coerceAtLeast(0.1),
                movingSpeedBlocksPerSecond = arenaConfig.movingSpeedBlocksPerSecond.coerceAtLeast(0.0),
                ignoreFactions = arenaConfig.ignoreFactions,
                contestWhenMultipleCappers = arenaConfig.contestWhenMultipleCappers,
                flaresMustBePlacedOnCap = arenaConfig.flaresMustBePlacedOnCap,
                schedule = arenaConfig.schedule,
                rewards = arenaConfig.rewards,
                chancedRewards = arenaConfig.chancedRewards,
                captureSpeedBonuses = arenaConfig.captureSpeedBonuses,
            )
        }.toMap()
    }

    fun reload() = plugin.reloadConfig()
}

private fun section(config: ConfigurationSection, path: String): ConfigurationSection? = config.getConfigurationSection(path)
private fun boolean(config: ConfigurationSection, path: String, default: Boolean): Boolean = if (config.contains(path)) config.getBoolean(path) else default
private fun integer(config: ConfigurationSection, path: String, default: Int): Int = if (config.contains(path)) config.getInt(path) else default
private fun decimal(config: ConfigurationSection, path: String, default: Double): Double = if (config.contains(path)) config.getDouble(path) else default
private fun string(config: ConfigurationSection, path: String, default: String): String = config.getString(path) ?: default
private fun strings(config: ConfigurationSection, path: String): List<String> = config.getStringList(path)

private fun position(config: ConfigurationSection, path: String, x: Double = 0.0, y: Double = 80.0, z: Double = 0.0) =
    section(config, path)?.let { point ->
        net.badgersmc.ek.config.PositionConfig(decimal(point, "x", x), decimal(point, "y", y), decimal(point, "z", z))
    } ?: net.badgersmc.ek.config.PositionConfig(x, y, z)

private object ManualStartConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.ManualStartConfig(
        enabled = boolean(config, "manual-start.enabled", true),
        basicCost = decimal(config, "manual-start.basic-cost", 0.0).coerceAtLeast(0.0),
        advancedCost = decimal(config, "manual-start.advanced-cost", 0.0).coerceAtLeast(0.0),
        delaySeconds = integer(config, "manual-start.delay-seconds", 0).coerceAtLeast(0),
    )
}

private object ScheduleConfigLoader {
    fun load(config: FileConfiguration, zone: ZoneId) = net.badgersmc.ek.config.ScheduleConfig(
        enabled = boolean(config, "schedule.enabled", false),
        zone = zone,
        preStartWarningSeconds = integer(config, "schedule.pre-start-warning-seconds", 300).coerceAtLeast(0),
        times = strings(config, "schedule.times"),
    )
}

private object FlareConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.FlareConfig(
        enabled = boolean(config, "flares.enabled", true),
        item = net.badgersmc.ek.config.FlareItemConfig(
            material = string(config, "flares.item.material", "REDSTONE_TORCH"),
            name = string(config, "flares.item.name", "&c{KOTH} Koth Flare"),
            lore = strings(config, "flares.item.lore").ifEmpty { listOf("&7Right-Click to start a &c{KOTH} &7koth!") },
        ),
    )
}

private object ProgressBarConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.ProgressBarConfig(
        enabled = boolean(config, "progress-bar.enabled", true),
        length = integer(config, "progress-bar.length", 10).coerceIn(1, 100),
        character = string(config, "progress-bar.character", "|").ifEmpty { "|" },
    )
}

private object ReminderConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.ReminderConfig(
        enabled = boolean(config, "reminders.enabled", true),
        intervalSeconds = integer(config, "reminders.interval-seconds", 300).coerceAtLeast(1),
    )
}

private object ArenaConfigLoader {
    fun load(config: FileConfiguration): Map<String, ArenaConfig> {
        val arenas = section(config, "arenas") ?: return emptyMap()
        return arenas.getKeys(false).associateWith { id ->
            val arena = arenas.getConfigurationSection(id) ?: return@associateWith ArenaConfig()
            ArenaConfig(
                enabled = boolean(arena, "enabled", true),
                family = string(arena, "family", "capture").lowercase(),
                world = string(arena, "world", "world"),
                center = position(arena, "center"),
                protectedRegion = net.badgersmc.ek.config.ProtectedRegionConfig(
                    corner1 = position(arena, "protected-region.corner-1", -32.0, -64.0, -32.0),
                    corner2 = position(arena, "protected-region.corner-2", 32.0, 320.0, 32.0),
                ),
                radius = decimal(arena, "radius", 5.0).coerceAtLeast(0.1),
                durationSeconds = integer(arena, "duration-seconds", 900).coerceAtLeast(1),
                captureSeconds = integer(arena, "capture-seconds", 120).coerceAtLeast(1),
                leaveBehavior = string(arena, "leave-behavior", "RESET"),
                decayPerSecond = decimal(arena, "decay-per-second", 1.0).coerceAtLeast(0.0),
                movingSquareSize = decimal(arena, "square-size", 20.0).coerceAtLeast(0.1),
                movingSpeedBlocksPerSecond = decimal(arena, "speed-blocks-per-second", 1.0).coerceAtLeast(0.0),
                ignoreFactions = boolean(arena, "ignore-factions", false),
                contestWhenMultipleCappers = boolean(arena, "contest-when-multiple-cappers", true),
                flaresMustBePlacedOnCap = boolean(arena, "flares-must-be-placed-on-cap", true),
                schedule = strings(arena, "schedule"),
                rewards = strings(arena, "rewards"),
                chancedRewards = chancedRewards(arena, "chanced-rewards"),
                captureSpeedBonuses = captureBonuses(arena, "capture-speed-bonuses"),
            )
        }
    }

    private fun chancedRewards(config: ConfigurationSection, path: String): Map<String, Double> {
        val values = section(config, path) ?: return emptyMap()
        return buildMap {
            values.getKeys(false).forEach { chanceText ->
                val command = values.getString(chanceText) ?: return@forEach
                val chance = chanceText.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: return@forEach
                put(command, chance)
            }
        }
    }

    private fun captureBonuses(config: ConfigurationSection, path: String): Map<Int, Double> {
        val values = section(config, path) ?: return emptyMap()
        return buildMap {
            values.getKeys(false).forEach { countText ->
                val count = countText.toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                val multiplier = decimal(values, countText, 1.0).takeIf { it > 0.0 } ?: return@forEach
                put(count, multiplier)
            }
        }
    }
}

private object RewardConfigLoader {
    fun load(config: FileConfiguration): Map<String, net.badgersmc.ek.config.RewardConfig> {
        val rewards = section(config, "rewards") ?: return emptyMap()
        return rewards.getKeys(false).associateWith { family ->
            val values = rewards.getConfigurationSection(family)
            net.badgersmc.ek.config.RewardConfig(
                soloVaultMoney = values?.let { decimal(it, "solo-vault-money", 0.0) }?.coerceAtLeast(0.0) ?: 0.0,
                guildVaultMoney = values?.let { decimal(it, "guild-vault-money", 0.0) }?.coerceAtLeast(0.0) ?: 0.0,
            )
        }
    }
}

private object DiscordConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.DiscordConfig(
        enabled = boolean(config, "discord.enabled", false),
        webhookUrl = string(config, "discord.webhook-url", ""),
        preStartPingMinutes = integer(config, "discord.pre-start-ping-minutes", 10).coerceAtLeast(0),
        liveUpdateSeconds = integer(config, "discord.live-update-seconds", 60).coerceAtLeast(1),
    )
}

private object PrivateTestingConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.PrivateTestingConfig(
        lobbySeconds = integer(config, "private-testing.lobby-seconds", 0).coerceAtLeast(0),
        quickMatchDurationSeconds = integer(config, "private-testing.quick-match-duration-seconds", 120).coerceAtLeast(1),
        quickCaptureSeconds = integer(config, "private-testing.quick-capture-seconds", 15).coerceAtLeast(1),
        showObjectiveParticles = boolean(config, "private-testing.show-objective-particles", true),
    )
}

private object LockConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.LockConfig(
        state = runCatching { LockState.valueOf(string(config, "locks.state", "UNLOCKED").uppercase()) }.getOrDefault(LockState.UNLOCKED),
    )
}

private object DisplayConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.DisplayConfig(boolean(config, "display.zone-border", true))
}

private object RulesConfigLoader {
    fun load(config: FileConfiguration) = net.badgersmc.ek.config.FamilyRulesConfig(
        rules = mapOf(
            "capture" to ruleSet(config, "rules.defaults.capture"),
            "moving" to ruleSet(config, "rules.defaults.moving"),
            "conquest" to ruleSet(config, "rules.defaults.conquest"),
        ),
    )

    private fun ruleSet(config: FileConfiguration, path: String): net.badgersmc.ek.infrastructure.restriction.RuleSet {
        val values = section(config, path) ?: return net.badgersmc.ek.infrastructure.restriction.RuleSet.PERMISSIVE
        return net.badgersmc.ek.infrastructure.restriction.RuleSet(
            elytraAllowed = boolean(values, "elytra", true),
            maceRule = runCatching { MaceRule.valueOf(string(values, "mace", "FULLY_ALLOWED").uppercase()) }
                .getOrDefault(MaceRule.FULLY_ALLOWED),
            spearAllowed = boolean(values, "spear", true),
            enderPearlAllowed = boolean(values, "ender-pearl", true),
            windChargeAllowed = boolean(values, "wind-charge", true),
            maceCooldownSeconds = integer(values, "mace-cooldown-seconds", 0).coerceAtLeast(0),
            spearCooldownSeconds = integer(values, "spear-cooldown-seconds", 0).coerceAtLeast(0),
            enderPearlCooldownSeconds = integer(values, "ender-pearl-cooldown-seconds", 0).coerceAtLeast(0),
            windChargeCooldownSeconds = integer(values, "wind-charge-cooldown-seconds", 0).coerceAtLeast(0),
        )
    }
}
